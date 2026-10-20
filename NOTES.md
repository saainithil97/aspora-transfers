# NOTES

## Ticket 1: two instances behind a load balancer

### How to see it

`src/test/java/.../TwoInstancesTest.java` boots **two full copies of the service** in one JVM. Each has its own
web server on a random port, its own dispatcher and its own connection pool, and both point at the one Postgres
from `make up`. Requests are spread across both copies, and both dispatchers are fired at the same instant.

```
make up && make migrate && make test        # or: ./gradlew test --tests '*TwoInstancesTest'
```

To see every case fail on the original code, put the original `src/main` back while keeping the new test. Reset
the DB first, because the red run leaves duplicate keys behind and `V2` would then refuse to apply:

```
git checkout b24bdb7eae66f36cff8327eaf772dabd080b86fc -- src/main
make down && make up && make migrate && ./gradlew test --tests '*TwoInstancesTest'   # red
git checkout HEAD -- src/main
make down && make up && make migrate && make test                                     # green
```

On the original code:

| test | original code |
|---|---|
| same `Idempotency-Key` sent 20× across both | 20 transfers created, debited 20× |
| 20 concurrent sends against a limit that allows 5 | 20 succeeded |
| 40 payouts, two dispatchers | some sent to the rail twice |
| the same FAILED webhook 10× across both | refunded 9×, ₹9,045 created from nothing |
| COMPLETED then FAILED | flipped to FAILED and refunded a paid transfer |

### Assumptions found and what changed

1. **Idempotency was check-then-insert, with no constraint behind it.** (`TransferService.create`,
   `transfers_idempotency_key_idx` was not unique.) Two requests with one key both missed the lookup, and both
   debited.
   *Fix:* `V2` makes `idempotency_key` unique. Inside the create transaction we take the user lock (see 2),
   then look the key up again, so a retry that queued behind the original is answered as a replay instead of
   being judged on funds or limit. If a request with the same key but a different user's body slips past
   (different lock), the unique index rejects it, and the `DuplicateKeyException` is turned into a replay or
   an `IDEMPOTENCY_MISMATCH`.

2. **The daily limit was read outside the transaction, with no lock.** (`DailyLimitChecker`.) N concurrent
   requests all saw the same "sent today" total. A Java lock would not help, because the requests are in
   different processes.
   *Fix:* `DailyLimitChecker.lockUser` takes `pg_advisory_xact_lock(hashtextextended(user_id))` inside the
   create transaction, before any row lock. The limit check and the insert happen under it, and it is held
   until commit, so the next sender's sum includes this transfer. The lock order is always user, then
   accounts (sorted), and each transaction takes only one user lock, so this adds no deadlock cycle. I chose
   a per-user lock rather than locking the account row because the limit is per **user**, and a user can
   have several accounts.

3. **The dispatcher deduplicated with an in-memory `inFlight` set, and `markDispatched` was read-then-write.**
   Both instances read the same PENDING ids, both read status PENDING, and both sent.
   *Fix:* `TransferService.claimForDispatch` is a compare-and-set:
   `update ... set status='DISPATCHED' where id=? and status='PENDING'`. Only the caller that changes a row
   sends. Postgres re-checks the `where` clause after the row lock, so the losing instance gets 0 rows.
   `inFlight` stays as a local optimisation only.

4. **The webhook handler applied every delivery, with no transition check.** Rails redeliver. With two
   instances, two copies can land at once, and each one refunded. A FAILED arriving after COMPLETED overwrote
   it and refunded a transfer that was paid.
   *Fix:* the handler records the event (still kept for audit) and then compare-and-sets
   `DISPATCHED -> outcome`. Only the delivery that wins refunds. A duplicate is logged and answered with 204.
   A *conflicting* outcome changes no money and is logged as an error for review. `fail()` (rail refusal)
   uses the same guard. `V2` also makes `rail_ref` unique, so one webhook can never match two transfers.

5. **Crash between steps in dispatch.** The steps are: claim, then rail send, then record `rail_ref`. Every
   money movement (debit, refund) is one transaction together with its status change, so a crash cannot split
   those. The gap that remains is the rail call itself. I chose **at-most-once**: the claim commits before the
   send, and the dispatcher never resends a DISPATCHED transfer. A crash or an unknown error after the claim
   leaves the transfer `DISPATCHED` with `rail_ref is null`. It is **not** resent, because resending is only
   safe if the partner deduplicates, and we don't know that for an integration that doesn't exist yet. It is
   also **not** refunded, because we don't know the partner didn't pay. `RailClient` now requires
   implementations to send `transfer.id()` as our reference, so these can be settled by a status lookup.
   That reconciler is Ticket 2. It now resends these only when the rail confirms it has no record of them. Test: `payoutWhoseSendOutcomeIsUnknownIsNeverResentOrRefunded`.

### Found, deliberately not fixed (Ticket 1)

- **FakeRailClient webhooks go to `localhost:<own port>`, held in an in-memory scheduler.** A crash loses
  pending callbacks, and in production the callback should target the load balancer. This is the simulated
  partner, not our service. The lost callbacks are what Ticket 2 handles.
- **A webhook that beats `setRailRef`** gets 404, because the transfer is not yet findable by `rail_ref`. That
  is fine if the partner retries, and the Ticket 2 reconciler catches it if not. A real integration would
  match on our reference (`transfer.id`) instead.
- **`FeeRuleRegistry` caches `fee_rules` per process at startup.** After a fee change, two instances can charge
  different fees until both restart. Each transfer stores its own `fee_minor`, so refunds stay correct. It is
  a consistency issue for pricing, not one of the money guarantees. A restart on rollout is enough for now.
- **Idempotency keys are global, not scoped per user.** One user's key can replay another user's transfer (and
  leak it in the response). This is not a multi-instance problem, but it should be scoped to
  `(user, key)` once there is auth.
- **The rail refusal (`RailUnavailableException`) is treated as definitive.** It is in the fake. For a real
  partner, a timeout must be classed as "unknown" (point 5), not as "refused", or we refund a transfer that
  may have been paid.
- **Migration `V2` fails if duplicate idempotency keys already exist.** That is intended: never silently
  delete money records. Before deploying, run
  `select idempotency_key, count(*) from transfers group by 1 having count(*) > 1` and resolve the results by
  hand.
- **Unique `rail_ref` has a side effect.** If the rail ever hands back a ref we already hold, `setRailRef`
  fails and the transfer stays DISPATCHED without a ref. It is not refunded and is visible for
  reconciliation. That is the right outcome. It surfaced a test stub that reused `RR-TEST-1, 2, ...` across
  runs, which is now fixed.
- **Flyway on two instances starting at once is already safe.** It takes a Postgres advisory lock while
  migrating.

## Ticket 2: the partner drops webhooks

### What changed

- **Rail interface:** `RailClient.status(UUID transferId) → RailLookup(state, railRef)`. `state` is COMPLETED,
  FAILED, PROCESSING or NOT_FOUND. It throws `RailUnavailableException` when the rail can't be asked, and that
  means *unknown*, not NOT_FOUND. We look up by **our** transfer id, so a transfer we never got a `rail_ref`
  for (Ticket 1's crash gap) can still be answered.
- **Fake rail** (`FakeRailClient`):
  - drops `RAIL_WEBHOOK_DROP_RATE` (default 0.02, the 1 in 50) of webhooks
  - answers lookups, failing `RAIL_LOOKUP_FAILURE_RATE` of them
  - deduplicates sends on our transfer id

  Its books are the `fake_rail_payments` table, so two instances talk to **one** partner. With in-memory books,
  instance B would get NOT_FOUND for instance A's payouts and resend them.
- **New status `NEEDS_REVIEW`**, with a `review_reason`. Transitions: `DISPATCHED → NEEDS_REVIEW` and
  `NEEDS_REVIEW → COMPLETED | FAILED`. It still counts toward the daily limit, because the money has left the
  account. Ops can see these transfers at `GET /internal/transfers?status=NEEDS_REVIEW`.
- **`DISPATCHED → PENDING` is now legal**, which reverses an assertion in `TransferStateTest`. It's used only
  when the rail confirms it never received the transfer.
- **`dispatch/Reconciler`** runs on every instance. It claims due DISPATCHED rows using `next_check_at` (set to
  dispatch time + `RECONCILE_GRACE_MS`) with `FOR UPDATE SKIP LOCKED`. Claiming pushes `next_check_at` forward
  with exponential backoff. That push is both the retry schedule and a lease, so two instances never look up
  the same row at once. The rail is called **outside** any transaction, and lookups run on a pool of 8.

  | rail says | we do |
  |---|---|
  | COMPLETED | settle COMPLETED |
  | FAILED | settle FAILED and refund. The lookup answer is stored in `partner_events` (`LOOKUP_FAILED`) in the same transaction, as the evidence Finance asked for |
  | PROCESSING | ask again later. After `RECONCILE_MAX_PROCESSING_MS` (24h), set `NEEDS_REVIEW` with reason `RAIL_STILL_PROCESSING` |
  | NOT_FOUND, no `rail_ref` | the send never landed: back to PENDING and **sent again**, up to `RECONCILE_MAX_RESENDS`, then `NEEDS_REVIEW` with reason `RAIL_NOT_FOUND_AFTER_RESENDS` |
  | NOT_FOUND, has `rail_ref` | the rail contradicts itself: `NEEDS_REVIEW` with reason `RAIL_LOST_ACCEPTED_TRANSFER`. Not resent |
  | lookup fails | retry with backoff. After `RECONCILE_MAX_ATTEMPTS`, `NEEDS_REVIEW` with reason `RAIL_STATUS_UNKNOWN`. **Never refunded** |

- **`TransferService.settle()`** is the one place the rail's outcome is applied, whether it came by webhook or
  by lookup. It is a single `UPDATE … WHERE status IN ('DISPATCHED','NEEDS_REVIEW')`, and the refund runs only
  for the caller that changed the row. So a late webhook racing a lookup refunds once, and a webhook that
  arrives after we gave up still settles the transfer.

So **no transfer stays in DISPATCHED indefinitely**. It always ends in COMPLETED, FAILED (refunded only on the
partner's word), PENDING (resent), or NEEDS_REVIEW.

### Why resending is safe enough, and what it relies on

Resending after NOT_FOUND was a product choice (better for the user than refunding). It is only as safe as
NOT_FOUND is true, so it has three safeguards:
1. We never ask within the grace period, so a send that timed out on our side has time to land.
2. We only resend when we never got a `rail_ref`.
3. Resends are capped.

**The real partner should also deduplicate on our transfer id.** That is the one thing to confirm before going
live. If it can't, set `RECONCILE_MAX_RESENDS=1` (no automatic resend, so NOT_FOUND goes to review) until it can.

### Tests

- `ReconcilerTest` (10 tests, scripted rail): each row of the table above, plus: not looked up before the
  grace period; a late webhook settles a transfer in review; webhook + lookup both saying FAILED refund once;
  a settled transfer is never claimed again.
- **Manual end-to-end check with the real fake rail:** two `java -jar` processes on ports 8090/8091, one DB,
  `RAIL_WEBHOOK_DROP_RATE=0.5`, 40 payouts split across both.
  - All 40 settled within about 5s: 10 by lookup, 27 by webhook, 3 refused by the rail when sent.
  - Both instances' reconcilers did work.
  - Our status matches the partner's books for every transfer, nothing was paid twice, each FAILED transfer
    was refunded once, and the balance equals the expected value exactly.

### Cut or not done (Ticket 2)

- **No "resolve" endpoint for NEEDS_REVIEW** (this was a choice). Ops can see these transfers via the list
  endpoint. Resolving one today is a manual database step, done in one transaction:
  `update transfers set status = 'COMPLETED'|'FAILED' where id = ? and status = 'NEEDS_REVIEW'`, plus, for
  FAILED, the refund pair, as in `TransferService.refund`. The next step would be
  `POST /internal/transfers/{id}/resolve` calling `settle()`, with auth and an audit of who resolved it.
- **No alerting.** Moving to NEEDS_REVIEW logs at `ERROR`. A count of NEEDS_REVIEW transfers, and the age of
  the oldest DISPATCHED one, should be metrics with alerts.
- **Dev database clutter:** the V3 backfill marks every existing DISPATCHED row due. On a database that has
  run the tests, that includes rows created by test stub rails, which the fake rail has never heard of. They
  correctly go to NEEDS_REVIEW. Production would have only real rows, and the fake rail is local-only.
- **Ticket 1 follow-up:** the crash gap Ticket 1 left (DISPATCHED with no `rail_ref`) is now closed by the
  NOT_FOUND → resend and COMPLETED/FAILED rows of the table above.

## Backlog: retry failed payouts on a second rail

**Not built.** It changes what FAILED means and moves money a second time. That is a product and finance
decision, not a fix that belongs with these two tickets. Before building it I would need to know:

- **Which failures can be retried?** Only definite partner declines with a reason code. Never NEEDS_REVIEW,
  and never an unknown outcome, or we pay twice.
- **Does the user see a refund and then a new payout, or one transfer?** Today FAILED refunds straight away.
  Retrying means holding the money, so we would need a state like `RETRYING_ON_ALT_RAIL`, and FAILED would
  only mean "every rail failed".
- **Fees:** charged once, or per rail? Does the second rail cost more, and who pays?
- **Is the beneficiary reachable on rail B?** Does it support the same bank, account type and currency, and
  are the limits the same?
- **Rail B's guarantees:** its own status API, webhooks and deduplication. `rail_ref` becomes "per attempt",
  so we would need a `payout_attempts` table (transfer, rail, ref, outcome) instead of one column.
- **Ordering:** if rail A's FAILED is later reversed to COMPLETED (it happens), we have paid twice. We need
  each partner's reversal window and the recovery process for that case.

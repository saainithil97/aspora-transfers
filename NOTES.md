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
   That reconciler is Ticket 2. Test: `payoutWhoseSendOutcomeIsUnknownIsNeverResentOrRefunded`.

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

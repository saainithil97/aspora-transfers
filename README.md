# transfers

A small money-movement service. Users hold INR accounts (balances in paise). A transfer is either
**INTERNAL** (account to account, settled immediately) or a **PAYOUT** (sent to an external
beneficiary over a partner rail, settled asynchronously). Every balance change is mirrored by ledger
entries, every request carries an idempotency key, and each user has a daily sending limit.
The rail is simulated in-process so the whole thing runs on a laptop.

## Architecture

```
  client ──HTTP──▶  api/           ──▶  domain/TransferService  ──▶  store/   ──▶  Postgres
                    (controllers)        limits/  fees/               (JdbcClient, SQL)

  dispatch/Dispatcher ──(every DISPATCH_INTERVAL_MS)──▶ PENDING payouts ──▶ rail/RailClient
                                                                                  │
  api/WebhookController  ◀── POST /internal/rail/webhook (signed) ◀── rail calls back later
          └──▶ rail/RailWebhookHandler ──▶ store/
```

- `api/` HTTP in and out, error mapping. No business rules.
- `domain/` types, the status machine (`TransferState`), and `TransferService`, which owns money movement.
- `store/` one class per table, explicit SQL via `JdbcClient`.
- `limits/` the daily limit. `fees/` fee rules loaded from `fee_rules`. `rail/` the partner rail and its webhook.
- `dispatch/` the background loop that hands PENDING payouts to the rail.

## Running it

Needs Docker and a JDK 21+. Gradle comes with the repo (`./gradlew`).

```
cp .env.example .env     # optional; edit if port 5432 is taken on your machine
make up                  # Postgres 16 in Docker
make migrate             # Flyway migrations (src/main/resources/db/migration)
make seed                # 3 users with ₹1,00,000.00 each + the PAYOUT fee rule
make run                 # service on :8080
make test                # unit + integration tests (integration tests skip if Postgres is down)
make run RAIL_FAILURE_RATE=0 RAIL_WEBHOOK_ENABLED=false   # quiet rail: no random failures, no async webhooks (pass vars after make, .env is loaded by the Makefile)
make blast ARGS="10"     # fire concurrent POST /transfers, see scripts/blast.sh
make down                # stop Postgres and drop its data
```

Seeded accounts (fixed ids so you can paste them):

| user      | account id                             |
|-----------|----------------------------------------|
| `u-alice` | `11111111-1111-1111-1111-111111111111` |
| `u-bob`   | `22222222-2222-2222-2222-222222222222` |
| `u-carol` | `33333333-3333-3333-3333-333333333333` |

Example:

```
curl -s -X POST localhost:8080/transfers \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"type":"INTERNAL","fromAccountId":"11111111-1111-1111-1111-111111111111",
       "toAccountId":"22222222-2222-2222-2222-222222222222","amountMinor":150000,"currency":"INR"}'
```

Configuration is by environment variable (see `.env.example`): `DATABASE_URL`, `PORT`,
`RAIL_FAILURE_RATE` (0.2), `RAIL_LATENCY_MS` (300), `RAIL_WEBHOOK_SECRET`, `DISPATCH_INTERVAL_MS` (500),
`DAILY_LIMIT_MINOR` (5000000 = ₹50,000.00), `RAIL_WEBHOOK_DROP_RATE` (0.02), `RAIL_LOOKUP_FAILURE_RATE` (0),
`RECONCILE_INTERVAL_MS` (5000), `RECONCILE_GRACE_MS` (30000), `RECONCILE_RETRY_MS` (30000),
`RECONCILE_MAX_ATTEMPTS` (10), `RECONCILE_MAX_RESENDS` (3), `RECONCILE_MAX_PROCESSING_MS` (86400000).

## API

All bodies are JSON. Amounts are integer minor units. Errors look like `{"error":"CODE","message":"..."}`
with codes `VALIDATION` 400, `NOT_FOUND` 404, `UNAUTHORIZED` 401, `IDEMPOTENCY_MISMATCH` 409,
`INVALID_TRANSITION` 409, `INSUFFICIENT_FUNDS` 422, `LIMIT_EXCEEDED` 422.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/accounts` | `{userId, currency, openingBalanceMinor}` → 201 account |
| `GET` | `/accounts/{id}` | `{id, userId, currency, balanceMinor, createdAt}` |
| `GET` | `/accounts/{id}/ledger?limit=50&before=<ISO instant>` | ledger entries, newest first |
| `GET` | `/accounts/{id}/transfers?limit=20&before=<ISO instant>` | transfers sent from the account, newest first |
| `POST` | `/transfers` | header `Idempotency-Key` required. Body `{type, fromAccountId, toAccountId?, beneficiaryRef?, amountMinor, currency}`. 201 on create, 200 when replaying a key |
| `GET` | `/transfers/{id}` | `{id, type, status, fromAccountId, toAccountId, beneficiaryRef, amountMinor, feeMinor, currency, railRef, failureReason, reviewReason, createdAt, updatedAt}` |
| `GET` | `/internal/transfers?status=NEEDS_REVIEW&limit=50` | ops: transfers in a status, oldest first |
| `POST` | `/internal/rail/webhook` | rail callback `{railRef, event}`, `X-Rail-Signature` = hex HMAC-SHA256 of the body → 204 |
| `GET` | `/healthz` | `{"status":"ok"}` |

Transfer statuses: `PENDING → DISPATCHED → COMPLETED | FAILED`, and `PENDING → FAILED`; also
`DISPATCHED → PENDING` (the rail never received it, send again), `DISPATCHED → NEEDS_REVIEW` (we cannot find out)
and `NEEDS_REVIEW → COMPLETED | FAILED`.
INTERNAL transfers are created `COMPLETED`. PAYOUTs are created `PENDING`, the dispatcher moves them to
`DISPATCHED` when it hands them to the rail, and the rail's webhook settles them. Webhooks that do not arrive
within `RECONCILE_GRACE_MS` are made up for by `dispatch/Reconciler`, which asks the rail's status API. A failed payout is refunded
(amount + fee) to the source account with reversing ledger entries.

## Conventions

- **One migration per change.** `src/main/resources/db/migration/V<n>__<what>.sql`, applied by Flyway. Never edit an applied migration.
- **Explicit transactions.** Money moves inside `TransactionTemplate` blocks in `TransferService`; row locks are `SELECT ... FOR UPDATE`. Stores are plain SQL and know nothing about transactions.
- **Where rules live.** Validation and money movement: `domain/TransferService`. Status changes go through `domain/TransferState`. The daily limit: `limits/DailyLimitChecker`. Fees: `fees/`. HTTP mapping only in `api/`.
- **Adding a fee rule type.** Implement `fees/FeeRule`, add a case to `FeeRuleRegistry.build` for the new `rule_type`, and ship a migration that inserts the `fee_rules` row. The registry refuses to start on a `rule_type` it does not know.
- **Tests.** Unit tests are plain JUnit. Integration tests hit the real Postgres from `make up` through HTTP and skip (with a message) when it is not reachable.

## Your task

Your task is in `TASK.md`.

## Rules of the round

- 60 minutes to build. AI tools are allowed; use whatever you normally use.
- Commit and push before the hour ends. Work in progress is fine; uncommitted work does not exist.
- The second hour is a live walkthrough: you run your code, show it working, and explain what you changed
  and why, without AI assistance. Expect questions about what happens under concurrency and failure.

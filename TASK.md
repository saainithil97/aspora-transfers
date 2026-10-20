# Your tasks (SDE-3)

You have 60 minutes. AI tools are allowed. Push to `main` in this repo before the hour ends; the second hour is a live walkthrough where you will run your code and explain it without AI.

You will not finish everything below in 60 minutes. That is expected. Decide what matters, do that well, and write down what you cut and why in `NOTES.md`. Scope you chose not to build, with a reason, counts as work.

## Ticket 1 — Platform: two instances next week

> We are moving this service to two instances behind a load balancer next week. Nothing else changes. Find everything in the service that assumes it is the only process running, and fix it so the guarantees we make to users (no double send, no double debit, limits hold) still hold across instances and across a crash between steps.

Acceptance: you can name each assumption you found and show, with a test or a reproduction, that your fix holds with two copies of the service pointed at one database. Say what you did not fix and why.

## Ticket 2 — Ops: the partner drops webhooks

> The rail has started dropping webhooks. About one in fifty never arrives. We have transfers sitting in DISPATCHED for days. Finance will not accept a refund on a transfer we cannot prove failed.

Acceptance:
- No transfer stays in `DISPATCHED` indefinitely.
- A transfer is only refunded when we know the partner did not pay. If we cannot find out, the transfer goes into a state a human can see and act on.
- The partner exposes a status lookup; add it to the rail interface and the fake rail. The real integration does not exist yet.

## Also on the backlog, not required

> Product would like failed payouts to retry automatically on a second partner rail.

Do this only if you believe it belongs in this change. Either way, say in `NOTES.md` what you would need to know before building it.

## Deliverable

- Your changes pushed to `main`.
- `NOTES.md`: what you changed and where, what you cut and why, what you would need before building the backlog item, and anything you found that you deliberately did not touch.

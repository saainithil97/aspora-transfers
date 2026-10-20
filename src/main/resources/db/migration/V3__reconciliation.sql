-- Webhooks can go missing, so every DISPATCHED transfer is also checked with the rail's status API.
alter table transfers
  add column dispatched_at     timestamptz,
  add column dispatch_attempts int not null default 0,
  -- When the reconciler should next ask the rail. Also its lease: claiming a row pushes this forward, so
  -- another instance will not pick the same row up while the lookup is in flight.
  add column next_check_at     timestamptz,
  add column check_attempts    int not null default 0,
  -- Why a transfer was put in front of a human (status NEEDS_REVIEW).
  add column review_reason     text;

-- Transfers already sitting in DISPATCHED get looked at on the first reconciler run.
update transfers
set dispatched_at = updated_at, dispatch_attempts = 1, next_check_at = now()
where status = 'DISPATCHED';

create index transfers_reconcile_due_idx on transfers (next_check_at) where status = 'DISPATCHED';

-- The simulated partner's own books. Only FakeRailClient uses this; it lives in our database so that every
-- instance of the service talks to one "partner", as they would in production.
create table fake_rail_payments (
  transfer_id uuid primary key,
  rail_ref    text not null unique,
  outcome     text not null,
  settles_at  timestamptz not null,
  created_at  timestamptz not null default now()
);

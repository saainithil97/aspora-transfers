create table accounts (
  id            uuid primary key,
  user_id       text not null,
  currency      char(3) not null,
  balance_minor bigint not null check (balance_minor >= 0),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index accounts_user_id_idx on accounts (user_id);

create table transfers (
  id              uuid primary key,
  idempotency_key text not null,
  request_hash    text not null,
  type            text not null,
  status          text not null,
  from_account_id uuid not null references accounts (id),
  to_account_id   uuid references accounts (id),
  beneficiary_ref text,
  amount_minor    bigint not null check (amount_minor > 0),
  fee_minor       bigint not null default 0,
  currency        char(3) not null,
  rail_ref        text,
  failure_reason  text,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);
create index transfers_from_account_created_idx on transfers (from_account_id, created_at);
create index transfers_status_created_idx on transfers (status, created_at);
create index transfers_idempotency_key_idx on transfers (idempotency_key);

create table ledger_entries (
  id           bigserial primary key,
  transfer_id  uuid not null references transfers (id),
  account_id   uuid references accounts (id),
  direction    text not null,
  amount_minor bigint not null,
  created_at   timestamptz not null default now()
);
create index ledger_entries_account_created_idx on ledger_entries (account_id, created_at);

create table fee_rules (
  id            serial primary key,
  transfer_type text not null,
  currency      char(3) not null,
  rule_type     text not null,
  params        jsonb not null,
  active        boolean not null default true
);

create table partner_events (
  id          bigserial primary key,
  transfer_id uuid not null,
  event_type  text not null,
  rail_ref    text,
  payload     jsonb,
  received_at timestamptz not null default now()
);

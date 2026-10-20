insert into accounts (id, user_id, currency, balance_minor) values
  ('11111111-1111-1111-1111-111111111111', 'u-alice', 'INR', 10000000),
  ('22222222-2222-2222-2222-222222222222', 'u-bob',   'INR', 10000000),
  ('33333333-3333-3333-3333-333333333333', 'u-carol', 'INR', 10000000)
on conflict (id) do nothing;

insert into fee_rules (transfer_type, currency, rule_type, params)
select 'PAYOUT', 'INR', 'FLAT', '{"amountMinor": 500}'::jsonb
where not exists (
  select 1 from fee_rules where transfer_type = 'PAYOUT' and currency = 'INR' and active
);

select id, user_id, currency, balance_minor from accounts order by user_id;
select id, transfer_type, currency, rule_type, params from fee_rules where active order by id;

-- An idempotency key identifies at most one transfer. This is what stops two instances that both pass the
-- "have I seen this key?" check from both debiting: the second insert fails and is answered as a replay.
-- Will fail to apply if duplicates already exist; see NOTES.md for the pre-deploy check.
drop index transfers_idempotency_key_idx;
create unique index transfers_idempotency_key_uidx on transfers (idempotency_key);

-- A rail reference belongs to one transfer, so a webhook can never settle two.
create unique index transfers_rail_ref_uidx on transfers (rail_ref) where rail_ref is not null;

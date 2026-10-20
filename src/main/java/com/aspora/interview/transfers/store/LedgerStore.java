package com.aspora.interview.transfers.store;

import com.aspora.interview.transfers.domain.LedgerEntry;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerStore {

    private static final RowMapper<LedgerEntry> ROW = (rs, i) -> new LedgerEntry(
            rs.getLong("id"),
            rs.getObject("transfer_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            LedgerEntry.Direction.valueOf(rs.getString("direction")),
            rs.getLong("amount_minor"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient db;

    public LedgerStore(JdbcClient db) {
        this.db = db;
    }

    /** accountId may be null: that is the external (rail) side of a PAYOUT. */
    public void insert(UUID transferId, UUID accountId, LedgerEntry.Direction direction, long amountMinor) {
        db.sql("""
                insert into ledger_entries (transfer_id, account_id, direction, amount_minor)
                values (:transfer, :account, :direction, :amount)
                """)
                .param("transfer", transferId)
                .param("account", accountId, Types.OTHER)
                .param("direction", direction.name())
                .param("amount", amountMinor)
                .update();
    }

    public List<LedgerEntry> listByAccount(UUID accountId, Instant before, int limit) {
        return db.sql("""
                select id, transfer_id, account_id, direction, amount_minor, created_at
                from ledger_entries
                where account_id = :account and created_at < :before
                order by created_at desc, id desc
                limit :limit
                """)
                .param("account", accountId)
                .param("before", TransferStore.cursor(before))
                .param("limit", limit)
                .query(ROW)
                .list();
    }
}

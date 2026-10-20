package com.aspora.interview.transfers.store;

import com.aspora.interview.transfers.domain.Transfer;
import com.aspora.interview.transfers.domain.TransferStatus;
import com.aspora.interview.transfers.domain.TransferType;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransferStore {

    private static final String COLUMNS = """
            id, idempotency_key, request_hash, type, status, from_account_id, to_account_id, beneficiary_ref,
            amount_minor, fee_minor, currency, rail_ref, failure_reason, created_at, updated_at
            """;

    private static final RowMapper<Transfer> ROW = (rs, i) -> new Transfer(
            rs.getObject("id", UUID.class),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            TransferType.valueOf(rs.getString("type")),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getObject("from_account_id", UUID.class),
            rs.getObject("to_account_id", UUID.class),
            rs.getString("beneficiary_ref"),
            rs.getLong("amount_minor"),
            rs.getLong("fee_minor"),
            rs.getString("currency"),
            rs.getString("rail_ref"),
            rs.getString("failure_reason"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient db;

    public TransferStore(JdbcClient db) {
        this.db = db;
    }

    public void insert(Transfer t) {
        db.sql("""
                insert into transfers (id, idempotency_key, request_hash, type, status, from_account_id, to_account_id,
                                       beneficiary_ref, amount_minor, fee_minor, currency)
                values (:id, :key, :hash, :type, :status, :from, :to, :beneficiaryRef, :amount, :fee, :currency)
                """)
                .param("id", t.id())
                .param("key", t.idempotencyKey())
                .param("hash", t.requestHash())
                .param("type", t.type().name())
                .param("status", t.status().name())
                .param("from", t.fromAccountId())
                .param("to", t.toAccountId(), Types.OTHER)
                .param("beneficiaryRef", t.beneficiaryRef(), Types.VARCHAR)
                .param("amount", t.amountMinor())
                .param("fee", t.feeMinor())
                .param("currency", t.currency())
                .update();
    }

    public Optional<Transfer> find(UUID id) {
        return db.sql("select " + COLUMNS + " from transfers where id = :id")
                .param("id", id)
                .query(ROW)
                .optional();
    }

    public Optional<Transfer> findByIdempotencyKey(String key) {
        return db.sql("select " + COLUMNS + " from transfers where idempotency_key = :key order by created_at limit 1")
                .param("key", key)
                .query(ROW)
                .optional();
    }

    public Optional<Transfer> findByRailRef(String railRef) {
        return db.sql("select " + COLUMNS + " from transfers where rail_ref = :ref order by created_at limit 1")
                .param("ref", railRef)
                .query(ROW)
                .optional();
    }

    /** Transfers debited from an account, newest first, optionally those created strictly before a cursor. */
    public List<Transfer> listByFromAccount(UUID accountId, Instant before, int limit) {
        return db.sql("select " + COLUMNS + """
                from transfers
                where from_account_id = :account and created_at < :before
                order by created_at desc, id desc
                limit :limit
                """)
                .param("account", accountId)
                .param("before", cursor(before))
                .param("limit", limit)
                .query(ROW)
                .list();
    }

    public List<UUID> pendingIds(int limit) {
        return db.sql("select id from transfers where status = 'PENDING' order by created_at limit :limit")
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    public void updateStatus(UUID id, TransferStatus status) {
        db.sql("update transfers set status = :status, updated_at = now() where id = :id")
                .param("status", status.name())
                .param("id", id)
                .update();
    }

    public void markFailed(UUID id, String reason) {
        db.sql("update transfers set status = 'FAILED', failure_reason = :reason, updated_at = now() where id = :id")
                .param("reason", reason)
                .param("id", id)
                .update();
    }

    public void setRailRef(UUID id, String railRef) {
        db.sql("update transfers set rail_ref = :ref, updated_at = now() where id = :id")
                .param("ref", railRef)
                .param("id", id)
                .update();
    }

    static OffsetDateTime cursor(Instant before) {
        return OffsetDateTime.ofInstant(before == null ? Instant.parse("9999-12-31T00:00:00Z") : before, ZoneOffset.UTC);
    }
}

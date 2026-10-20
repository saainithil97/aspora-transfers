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
            amount_minor, fee_minor, currency, rail_ref, failure_reason, review_reason, created_at, updated_at
            """;

    /** A DISPATCHED transfer the reconciler has claimed, with what it needs to decide. */
    public record ReconcileCandidate(UUID id, String railRef, int dispatchAttempts, int checkAttempts,
            Instant dispatchedAt) {
    }

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
            rs.getString("review_reason"),
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

    public List<Transfer> listByStatus(TransferStatus status, int limit) {
        return db.sql("select " + COLUMNS + " from transfers where status = :status order by updated_at limit :limit")
                .param("status", status.name())
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

    /**
     * Moves the transfer from {@code expected} to {@code next} only if it is still in {@code expected}.
     * Returns false when another request or instance got there first. Concurrent callers queue on the row
     * lock and Postgres re-checks the where clause, so exactly one of them wins.
     */
    public boolean compareAndSetStatus(UUID id, TransferStatus expected, TransferStatus next) {
        return db.sql("update transfers set status = :next, updated_at = now() where id = :id and status = :expected")
                .param("next", next.name())
                .param("expected", expected.name())
                .param("id", id)
                .update() == 1;
    }

    /**
     * PENDING to DISPATCHED, as {@link #compareAndSetStatus}, and starts the clock for the reconciler: the
     * first status lookup is due {@code graceMs} from now, if no webhook has settled it by then.
     */
    public boolean claimForDispatch(UUID id, long graceMs) {
        return db.sql("""
                update transfers
                set status = 'DISPATCHED', dispatched_at = now(), dispatch_attempts = dispatch_attempts + 1,
                    next_check_at = now() + make_interval(secs => :grace / 1000.0), check_attempts = 0,
                    updated_at = now()
                where id = :id and status = 'PENDING'
                """)
                .param("grace", graceMs)
                .param("id", id)
                .update() == 1;
    }

    /**
     * Claims up to {@code limit} DISPATCHED transfers whose next check is due. Pushing next_check_at forward
     * (exponential backoff from {@code retryMs}) is both the schedule for the next attempt and a lease: no
     * other instance sees these rows as due while this one is asking the rail. SKIP LOCKED lets two instances
     * claim side by side without waiting on each other.
     */
    public List<ReconcileCandidate> claimDueForReconcile(int limit, long retryMs) {
        return db.sql("""
                update transfers t
                set next_check_at = now() + make_interval(secs => :retry / 1000.0 * power(2, least(t.check_attempts, 6))),
                    check_attempts = t.check_attempts + 1
                where t.id in (
                    select id from transfers
                    where status = 'DISPATCHED' and next_check_at <= now()
                    order by next_check_at
                    limit :limit
                    for update skip locked)
                returning t.id, t.rail_ref, t.dispatch_attempts, t.check_attempts, t.dispatched_at
                """)
                .param("retry", retryMs)
                .param("limit", limit)
                .query((rs, i) -> new ReconcileCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("rail_ref"),
                        rs.getInt("dispatch_attempts"),
                        rs.getInt("check_attempts"),
                        rs.getObject("dispatched_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * Applies the rail's outcome if the transfer is still waiting on one (DISPATCHED, or NEEDS_REVIEW when the
     * answer arrives after we gave up). One statement, so a webhook and the reconciler cannot both win.
     */
    public boolean settle(UUID id, TransferStatus outcome, String failureReason) {
        return db.sql("""
                update transfers
                set status = :outcome,
                    failure_reason = case when :outcome = 'FAILED' then :reason else failure_reason end,
                    updated_at = now()
                where id = :id and status in ('DISPATCHED', 'NEEDS_REVIEW')
                """)
                .param("outcome", outcome.name())
                .param("reason", failureReason, Types.VARCHAR)
                .param("id", id)
                .update() == 1;
    }

    /** DISPATCHED to PENDING so the dispatcher sends it again. Only for a transfer the rail never received. */
    public boolean requeue(UUID id) {
        return db.sql("""
                update transfers set status = 'PENDING', next_check_at = null, updated_at = now()
                where id = :id and status = 'DISPATCHED' and rail_ref is null
                """)
                .param("id", id)
                .update() == 1;
    }

    public boolean markNeedsReview(UUID id, String reason) {
        return db.sql("""
                update transfers set status = 'NEEDS_REVIEW', review_reason = :reason, next_check_at = null,
                                     updated_at = now()
                where id = :id and status = 'DISPATCHED'
                """)
                .param("reason", reason)
                .param("id", id)
                .update() == 1;
    }

    /** As {@link #compareAndSetStatus} to FAILED, also recording why. */
    public boolean markFailed(UUID id, TransferStatus expected, String reason) {
        return db.sql("""
                update transfers set status = 'FAILED', failure_reason = :reason, updated_at = now()
                where id = :id and status = :expected
                """)
                .param("reason", reason)
                .param("expected", expected.name())
                .param("id", id)
                .update() == 1;
    }

    public void setRailRef(UUID id, String railRef) {
        db.sql("update transfers set rail_ref = :ref, updated_at = now() where id = :id and rail_ref is null")
                .param("ref", railRef)
                .param("id", id)
                .update();
    }

    static OffsetDateTime cursor(Instant before) {
        return OffsetDateTime.ofInstant(before == null ? Instant.parse("9999-12-31T00:00:00Z") : before, ZoneOffset.UTC);
    }
}

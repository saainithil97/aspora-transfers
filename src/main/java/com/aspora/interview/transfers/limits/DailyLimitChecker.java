package com.aspora.interview.transfers.limits;

import com.aspora.interview.transfers.config.AppProperties;
import com.aspora.interview.transfers.domain.ApiException;
import com.aspora.interview.transfers.domain.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Per-user daily cap: the amounts of everything a user has sent today (UTC) that has not failed,
 * plus the new amount, must stay within DAILY_LIMIT_MINOR.
 */
@Component
public class DailyLimitChecker {

    private final JdbcClient db;
    private final long dailyLimitMinor;

    public DailyLimitChecker(JdbcClient db, AppProperties props) {
        this.db = db;
        this.dailyLimitMinor = props.dailyLimitMinor();
    }

    /**
     * Takes a transaction-scoped Postgres advisory lock on the user, so every instance creating a transfer
     * for this user queues here until the holder commits. Must be called inside a transaction, before any
     * row lock, so the lock order is always user then accounts.
     */
    public void lockUser(String userId) {
        db.sql("select pg_advisory_xact_lock(hashtextextended(:userId, 0))")
                .param("userId", userId)
                .query()
                .singleRow();
    }

    /**
     * Must be called inside the transaction that inserts the transfer. Holding the user lock until commit
     * means the next sender's sum already includes this transfer. Advisory locks are re-entrant, so callers
     * that already hold it can call this freely.
     */
    public void check(String userId, long amountMinor) {
        lockUser(userId);
        long sentToday = sentToday(userId);
        if (sentToday + amountMinor > dailyLimitMinor) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED,
                    "daily limit of " + dailyLimitMinor + " minor units exceeded: " + sentToday
                            + " already sent today, requested " + amountMinor);
        }
    }

    long sentToday(String userId) {
        return db.sql("""
                select coalesce(sum(t.amount_minor), 0)
                from transfers t
                join accounts a on a.id = t.from_account_id
                where a.user_id = :userId
                  and t.status <> 'FAILED'
                  and t.created_at >= (date_trunc('day', now() at time zone 'utc') at time zone 'utc')
                """)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}

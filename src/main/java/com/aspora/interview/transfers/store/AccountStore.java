package com.aspora.interview.transfers.store;

import com.aspora.interview.transfers.domain.Account;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountStore {

    private static final RowMapper<Account> ROW = (rs, i) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("user_id"),
            rs.getString("currency"),
            rs.getLong("balance_minor"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient db;

    public AccountStore(JdbcClient db) {
        this.db = db;
    }

    public Account insert(String userId, String currency, long openingBalanceMinor) {
        UUID id = UUID.randomUUID();
        db.sql("""
                insert into accounts (id, user_id, currency, balance_minor)
                values (:id, :userId, :currency, :balance)
                """)
                .param("id", id)
                .param("userId", userId)
                .param("currency", currency)
                .param("balance", openingBalanceMinor)
                .update();
        return find(id).orElseThrow();
    }

    public Optional<Account> find(UUID id) {
        return db.sql("select id, user_id, currency, balance_minor, created_at from accounts where id = :id")
                .param("id", id)
                .query(ROW)
                .optional();
    }

    /** Reads the row under a row lock; must be called inside a transaction. */
    public Optional<Account> lockForUpdate(UUID id) {
        return db.sql("select id, user_id, currency, balance_minor, created_at from accounts where id = :id for update")
                .param("id", id)
                .query(ROW)
                .optional();
    }

    public void adjustBalance(UUID id, long deltaMinor) {
        db.sql("update accounts set balance_minor = balance_minor + :delta, updated_at = now() where id = :id")
                .param("delta", deltaMinor)
                .param("id", id)
                .update();
    }
}

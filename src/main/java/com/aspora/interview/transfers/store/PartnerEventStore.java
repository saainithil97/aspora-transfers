package com.aspora.interview.transfers.store;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Raw record of everything the rail tells us, kept for audit regardless of what we do with it. */
@Repository
public class PartnerEventStore {

    private final JdbcClient db;

    public PartnerEventStore(JdbcClient db) {
        this.db = db;
    }

    public void insert(UUID transferId, String eventType, String railRef, String payloadJson) {
        db.sql("""
                insert into partner_events (transfer_id, event_type, rail_ref, payload)
                values (:transfer, :event, :ref, cast(:payload as jsonb))
                """)
                .param("transfer", transferId)
                .param("event", eventType)
                .param("ref", railRef)
                .param("payload", payloadJson)
                .update();
    }
}

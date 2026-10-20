package com.aspora.interview.transfers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aspora.interview.transfers.dispatch.Dispatcher;
import com.aspora.interview.transfers.dispatch.Reconciler;
import com.aspora.interview.transfers.domain.Transfer;
import com.aspora.interview.transfers.rail.RailClient;
import com.aspora.interview.transfers.rail.RailLookup;
import com.aspora.interview.transfers.rail.RailUnavailableException;
import com.aspora.interview.transfers.rail.WebhookSignature;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Ticket 2: webhooks go missing, so DISPATCHED transfers are settled by asking the rail. The rail here is
 * scripted per transfer; the tests make a transfer "due" by moving its next_check_at instead of waiting.
 */
@RequiresPostgres
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "transfers.rail.webhook-enabled=false",
        "transfers.dispatch-interval-ms=3600000",
        "transfers.reconcile.interval-ms=3600000",
        "transfers.reconcile.grace-ms=60000",
        "transfers.reconcile.max-attempts=3",
        "transfers.reconcile.max-resends=2",
        "transfers.reconcile.max-processing-ms=3600000"})
class ReconcilerTest {

    /** What the scripted partner says about each transfer; anything not scripted is PROCESSING. */
    static final Map<UUID, RailLookup.State> PARTNER_SAYS = new ConcurrentHashMap<>();
    static final Set<UUID> LOOKUP_UNAVAILABLE = ConcurrentHashMap.newKeySet();
    static final Map<UUID, AtomicInteger> SENDS = new ConcurrentHashMap<>();
    static final Map<UUID, AtomicInteger> LOOKUPS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class ScriptedRail {

        @Bean
        @Primary
        RailClient scriptedRail() {
            return new RailClient() {
                @Override
                public String send(Transfer transfer) {
                    SENDS.computeIfAbsent(transfer.id(), k -> new AtomicInteger()).incrementAndGet();
                    if (transfer.beneficiaryRef().startsWith("BENE-LOST")) {
                        throw new IllegalStateException("answer lost on the way back");
                    }
                    return "RR-REC-" + UUID.randomUUID();
                }

                @Override
                public RailLookup status(UUID transferId) {
                    LOOKUPS.computeIfAbsent(transferId, k -> new AtomicInteger()).incrementAndGet();
                    if (LOOKUP_UNAVAILABLE.contains(transferId)) {
                        throw new RailUnavailableException("status lookup timed out");
                    }
                    RailLookup.State state = PARTNER_SAYS.getOrDefault(transferId, RailLookup.State.PROCESSING);
                    return new RailLookup(state, state == RailLookup.State.NOT_FOUND ? null : "RR-SEEN-" + transferId);
                }
            };
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcClient db;

    @Autowired
    Dispatcher dispatcher;

    @Autowired
    Reconciler reconciler;

    String from;

    @BeforeEach
    void account() {
        from = newAccount(1_000_000);
    }

    @Test
    void droppedWebhookPaidAtPartnerIsCompletedWithoutRefund() {
        UUID id = dispatchedPayout();
        long afterDebit = balance(from);
        PARTNER_SAYS.put(id, RailLookup.State.COMPLETED);

        reconcile(id);

        assertEquals("COMPLETED", status(id));
        assertEquals(afterDebit, balance(from));
        assertEquals(1, partnerEvents(id, "LOOKUP_COMPLETED"), "the answer is kept as evidence");
    }

    @Test
    void droppedWebhookFailedAtPartnerIsRefundedOnce() {
        UUID id = dispatchedPayout();
        PARTNER_SAYS.put(id, RailLookup.State.FAILED);

        reconcile(id);
        db.sql("update transfers set next_check_at = now() - interval '1 second' where id = :id").param("id", id).update();
        reconciler.tick();

        assertEquals(1, LOOKUPS.get(id).get(), "a settled transfer is never claimed again");
        assertEquals("FAILED", status(id));
        assertEquals(1_000_000, balance(from));
        assertEquals(4, ledgerEntries(id));
        assertEquals(1, partnerEvents(id, "LOOKUP_FAILED"), "proof the partner did not pay");
    }

    @Test
    void notAskedBeforeTheGracePeriod() {
        UUID id = dispatchedPayout();
        PARTNER_SAYS.put(id, RailLookup.State.NOT_FOUND);

        reconciler.tick();

        assertNull(LOOKUPS.get(id), "a just-dispatched transfer is left for its webhook");
        assertEquals("DISPATCHED", status(id));
    }

    @Test
    void stillProcessingStaysDispatchedUntilItIsTooOld() {
        UUID id = dispatchedPayout();
        PARTNER_SAYS.put(id, RailLookup.State.PROCESSING);

        reconcile(id);
        assertEquals("DISPATCHED", status(id));

        db.sql("update transfers set dispatched_at = now() - interval '2 hours' where id = :id").param("id", id).update();
        reconcile(id);

        assertEquals("NEEDS_REVIEW", status(id));
        assertEquals("RAIL_STILL_PROCESSING", reviewReason(id));
    }

    @Test
    void whenWeCannotFindOutItGoesToReviewAndIsNotRefunded() {
        UUID id = dispatchedPayout();
        long afterDebit = balance(from);
        LOOKUP_UNAVAILABLE.add(id);

        for (int i = 0; i < 3; i++) {
            assertEquals("DISPATCHED", status(id), "retried before giving up, attempt " + i);
            reconcile(id);
        }

        assertEquals("NEEDS_REVIEW", status(id));
        assertEquals("RAIL_STATUS_UNKNOWN", reviewReason(id));
        assertEquals(afterDebit, balance(from), "never refunded on a guess");

        JsonNode review = http.getForObject("/internal/transfers?status=NEEDS_REVIEW&limit=500", JsonNode.class);
        boolean listed = false;
        for (JsonNode t : review) {
            listed |= t.get("id").asText().equals(id.toString());
        }
        assertTrue(listed, "visible to ops");
    }

    @Test
    void neverReachedThePartnerIsSentAgain() {
        UUID id = payout("BENE-LOST-");
        dispatcher.tick();
        awaitStatus(id, "DISPATCHED");
        awaitSends(id, 1);
        PARTNER_SAYS.put(id, RailLookup.State.NOT_FOUND);

        reconcile(id);
        assertEquals("PENDING", status(id), "back in the queue");

        dispatcher.tick();
        awaitSends(id, 2);
        awaitStatus(id, "DISPATCHED");
    }

    @Test
    void notFoundAfterTheResendBudgetGoesToReview() {
        UUID id = payout("BENE-LOST-");
        PARTNER_SAYS.put(id, RailLookup.State.NOT_FOUND);
        for (int send = 1; send <= 2; send++) {
            dispatcher.tick();
            awaitSends(id, send);
            awaitStatus(id, "DISPATCHED");
            reconcile(id);
        }

        assertEquals("NEEDS_REVIEW", status(id));
        assertEquals("RAIL_NOT_FOUND_AFTER_RESENDS", reviewReason(id));
        assertEquals(2, SENDS.get(id).get());
    }

    @Test
    void notFoundForATransferThePartnerAcceptedGoesToReview() {
        UUID id = dispatchedPayout(); // has a rail_ref, so the partner did take it
        long afterDebit = balance(from);
        PARTNER_SAYS.put(id, RailLookup.State.NOT_FOUND);

        reconcile(id);

        assertEquals("NEEDS_REVIEW", status(id));
        assertEquals("RAIL_LOST_ACCEPTED_TRANSFER", reviewReason(id));
        assertEquals(1, SENDS.get(id).get(), "not resent");
        assertEquals(afterDebit, balance(from));
    }

    @Test
    void aLateWebhookStillSettlesATransferInReview() {
        UUID id = dispatchedPayout();
        LOOKUP_UNAVAILABLE.add(id);
        for (int i = 0; i < 3; i++) {
            reconcile(id);
        }
        assertEquals("NEEDS_REVIEW", status(id));

        assertEquals(HttpStatus.NO_CONTENT, webhook(railRef(id), "FAILED").getStatusCode());

        assertEquals("FAILED", status(id));
        assertEquals(1_000_000, balance(from));
    }

    @Test
    void lookupAndWebhookBothSayingFailedRefundOnce() {
        UUID id = dispatchedPayout();
        PARTNER_SAYS.put(id, RailLookup.State.FAILED);

        reconcile(id);
        assertEquals(HttpStatus.NO_CONTENT, webhook(railRef(id), "FAILED").getStatusCode());

        assertEquals(1_000_000, balance(from));
        assertEquals(4, ledgerEntries(id));
    }

    // ---- helpers ----

    private UUID dispatchedPayout() {
        UUID id = payout("BENE-");
        dispatcher.tick();
        awaitRailRef(id);
        return id;
    }

    private UUID payout(String beneficiaryPrefix) {
        Map<String, Object> body = Map.of("type", "PAYOUT", "fromAccountId", from,
                "beneficiaryRef", beneficiaryPrefix + UUID.randomUUID(), "amountMinor", 100_000, "currency", "INR");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> r = http.postForEntity("/transfers", new HttpEntity<>(body, headers), JsonNode.class);
        assertEquals(HttpStatus.CREATED, r.getStatusCode(), String.valueOf(r.getBody()));
        return UUID.fromString(r.getBody().get("id").asText());
    }

    /** Makes the transfer due now and runs the reconciler until it has been looked at once more. */
    private void reconcile(UUID id) {
        int before = LOOKUPS.containsKey(id) ? LOOKUPS.get(id).get() : 0;
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            db.sql("update transfers set next_check_at = now() - interval '1 second' where id = :id")
                    .param("id", id).update();
            reconciler.tick();
            if (LOOKUPS.containsKey(id) && LOOKUPS.get(id).get() > before) {
                return;
            }
        }
        throw new AssertionError("reconciler never looked at " + id + " (status " + status(id) + ")");
    }

    private ResponseEntity<Void> webhook(String railRef, String event) {
        String body = "{\"railRef\":\"" + railRef + "\",\"event\":\"" + event + "\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Rail-Signature", WebhookSignature.sign(secret(), body));
        return http.postForEntity("/internal/rail/webhook", new HttpEntity<>(body, headers), Void.class);
    }

    private static String secret() {
        String s = System.getenv("RAIL_WEBHOOK_SECRET");
        return s == null || s.isBlank() ? "rail-dev-secret" : s;
    }

    private void awaitRailRef(UUID id) {
        await(() -> railRef(id) != null, "rail_ref for " + id);
    }

    private void awaitStatus(UUID id, String expected) {
        await(() -> expected.equals(status(id)), id + " to be " + expected + " (is " + status(id) + ")");
    }

    private void awaitSends(UUID id, int n) {
        await(() -> SENDS.containsKey(id) && SENDS.get(id).get() >= n, n + " sends of " + id);
    }

    private static void await(java.util.function.BooleanSupplier condition, String what) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private String newAccount(long opening) {
        Map<String, Object> body = Map.of("userId", "u-" + UUID.randomUUID(), "currency", "INR",
                "openingBalanceMinor", opening);
        return http.postForEntity("/accounts", body, JsonNode.class).getBody().get("id").asText();
    }

    private long balance(String accountId) {
        return http.getForObject("/accounts/{id}", JsonNode.class, accountId).get("balanceMinor").asLong();
    }

    private String status(UUID id) {
        return db.sql("select status from transfers where id = :id").param("id", id).query(String.class).single();
    }

    private String railRef(UUID id) {
        return db.sql("select rail_ref from transfers where id = :id").param("id", id).query(String.class)
                .optional().orElse(null);
    }

    private String reviewReason(UUID id) {
        return db.sql("select review_reason from transfers where id = :id").param("id", id).query(String.class)
                .optional().orElse(null);
    }

    private int ledgerEntries(UUID id) {
        return db.sql("select count(*) from ledger_entries where transfer_id = :id").param("id", id)
                .query(Integer.class).single();
    }

    private int partnerEvents(UUID id, String type) {
        return db.sql("select count(*) from partner_events where transfer_id = :id and event_type = :t")
                .param("id", id).param("t", type).query(Integer.class).single();
    }
}

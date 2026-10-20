package com.aspora.interview.transfers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aspora.interview.transfers.dispatch.Dispatcher;
import com.aspora.interview.transfers.rail.RailClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

@RequiresPostgres
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "transfers.rail.webhook-enabled=false")
class TransfersApiTest {

    @TestConfiguration
    static class StubRail {

        private final AtomicInteger counter = new AtomicInteger();

        @Bean
        @Primary
        RailClient alwaysAcceptingRail() {
            // Unique across runs: rail_ref is unique in the database, which outlives this context.
            return transfer -> "RR-TEST-" + counter.incrementAndGet() + "-" + UUID.randomUUID();
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcClient db;

    @Autowired
    Dispatcher dispatcher;

    @Test
    void internalTransferMovesBalancesAndWritesTwoLedgerEntries() {
        String from = newAccount(1_000_000);
        String to = newAccount(0);

        ResponseEntity<JsonNode> created = post(from, internal(from, to, 250_000), UUID.randomUUID().toString());

        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        JsonNode transfer = created.getBody();
        assertEquals("INTERNAL", transfer.get("type").asText());
        assertEquals("COMPLETED", transfer.get("status").asText());
        assertEquals(0, transfer.get("feeMinor").asLong());
        assertEquals(750_000, balance(from));
        assertEquals(250_000, balance(to));

        Integer entries = db.sql("select count(*) from ledger_entries where transfer_id = :id")
                .param("id", UUID.fromString(transfer.get("id").asText()))
                .query(Integer.class).single();
        assertEquals(2, entries);
    }

    @Test
    void insufficientFundsIsRejected() {
        String from = newAccount(1_000);
        String to = newAccount(0);

        ResponseEntity<JsonNode> response = post(from, internal(from, to, 5_000), UUID.randomUUID().toString());

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals("INSUFFICIENT_FUNDS", response.getBody().get("error").asText());
        assertEquals(1_000, balance(from));
    }

    @Test
    void payoutIsPendingThenDispatched() throws Exception {
        String from = newAccount(1_000_000);

        ResponseEntity<JsonNode> created = post(from, payout(from, 100_000), UUID.randomUUID().toString());

        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertEquals("PENDING", created.getBody().get("status").asText());
        String id = created.getBody().get("id").asText();
        long debited = created.getBody().get("amountMinor").asLong() + created.getBody().get("feeMinor").asLong();
        assertEquals(1_000_000 - debited, balance(from));

        dispatcher.tick();
        JsonNode dispatched = awaitRailRef(id, Duration.ofSeconds(5));
        assertEquals("DISPATCHED", dispatched.get("status").asText());
        assertTrue(dispatched.get("railRef").asText().startsWith("RR-TEST-"));
        assertEquals(1_000_000 - debited, balance(from));
    }

    @Test
    void sameKeySameBodyReplaysTheOriginal() {
        String from = newAccount(1_000_000);
        String to = newAccount(0);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = internal(from, to, 10_000);

        ResponseEntity<JsonNode> first = post(from, body, key);
        ResponseEntity<JsonNode> second = post(from, body, key);

        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertEquals(first.getBody().get("id").asText(), second.getBody().get("id").asText());
        assertEquals(990_000, balance(from));
    }

    @Test
    void sameKeyDifferentBodyIsAConflict() {
        String from = newAccount(1_000_000);
        String to = newAccount(0);
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = post(from, internal(from, to, 10_000), key);
        ResponseEntity<JsonNode> second = post(from, internal(from, to, 20_000), key);

        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        assertEquals("IDEMPOTENCY_MISMATCH", second.getBody().get("error").asText());
        assertEquals(990_000, balance(from));
    }

    @Test
    void transferAboveTheDailyLimitIsRejected() {
        String from = newAccount(100_000_000);
        String to = newAccount(0);

        ResponseEntity<JsonNode> response = post(from, internal(from, to, 6_000_000), UUID.randomUUID().toString());

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals("LIMIT_EXCEEDED", response.getBody().get("error").asText());
        assertEquals(100_000_000, balance(from));
    }

    @Test
    void ledgerPaginatesNewestFirst() {
        String from = newAccount(1_000_000);
        String to = newAccount(0);
        for (long amount : new long[] {1_000, 2_000, 3_000}) {
            assertEquals(HttpStatus.CREATED, post(from, internal(from, to, amount), UUID.randomUUID().toString()).getStatusCode());
        }

        JsonNode page1 = http.getForObject("/accounts/{id}/ledger?limit=2", JsonNode.class, from);
        assertEquals(2, page1.size());
        assertEquals(3_000, page1.get(0).get("amountMinor").asLong());
        assertEquals(2_000, page1.get(1).get("amountMinor").asLong());

        String cursor = page1.get(1).get("createdAt").asText();
        JsonNode page2 = http.getForObject("/accounts/{id}/ledger?limit=2&before={before}", JsonNode.class, from, cursor);
        assertEquals(1, page2.size());
        assertEquals(1_000, page2.get(0).get("amountMinor").asLong());
        assertNotEquals(page1.get(0).get("id").asLong(), page2.get(0).get("id").asLong());
        assertEquals("DEBIT", page2.get(0).get("direction").asText());
    }

    // ---- helpers ----

    private String newAccount(long openingBalanceMinor) {
        Map<String, Object> body = Map.of("userId", "u-" + UUID.randomUUID(), "currency", "INR",
                "openingBalanceMinor", openingBalanceMinor);
        ResponseEntity<JsonNode> response = http.postForEntity("/accounts", body, JsonNode.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        return response.getBody().get("id").asText();
    }

    private long balance(String accountId) {
        return http.getForObject("/accounts/{id}", JsonNode.class, accountId).get("balanceMinor").asLong();
    }

    private ResponseEntity<JsonNode> post(String from, Map<String, Object> body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return http.exchange(new RequestEntity<>(body, headers, HttpMethod.POST, http.getRestTemplate().getUriTemplateHandler().expand("/transfers")), JsonNode.class);
    }

    private static Map<String, Object> internal(String from, String to, long amountMinor) {
        return Map.of("type", "INTERNAL", "fromAccountId", from, "toAccountId", to, "amountMinor", amountMinor, "currency", "INR");
    }

    private static Map<String, Object> payout(String from, long amountMinor) {
        return Map.of("type", "PAYOUT", "fromAccountId", from, "beneficiaryRef", "BENE-" + UUID.randomUUID(),
                "amountMinor", amountMinor, "currency", "INR");
    }

    /** The dispatcher works asynchronously; the rail reference is the last thing it writes. */
    private JsonNode awaitRailRef(String transferId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = http.getForObject("/transfers/{id}", JsonNode.class, transferId);
            if (!last.get("railRef").isNull()) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("transfer " + transferId + " was never handed to the rail, last seen: " + last);
    }
}

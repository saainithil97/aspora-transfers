package com.aspora.interview.transfers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aspora.interview.transfers.dispatch.Dispatcher;
import com.aspora.interview.transfers.domain.Transfer;
import com.aspora.interview.transfers.rail.RailClient;
import com.aspora.interview.transfers.rail.RailLookup;
import com.aspora.interview.transfers.rail.WebhookSignature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Boots two full copies of the service (own web server, own dispatcher, own connection pool) against the
 * one Postgres from `make up`, and checks that the user-facing guarantees hold when requests and
 * background work are spread across both.
 */
@RequiresPostgres
class TwoInstancesTest {

    private static final String SECRET = env("RAIL_WEBHOOK_SECRET", "rail-dev-secret");
    private static final long DAILY_LIMIT = 5_000_000;

    /** Shared by both instances: how many times the "partner" was asked to send each transfer. */
    static final Map<UUID, AtomicInteger> SENDS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class CountingRail {

        @Bean
        @Primary
        RailClient countingRail() {
            AtomicInteger refs = new AtomicInteger();
            return new RailClient() {
                @Override
                public String send(Transfer transfer) {
                    SENDS.computeIfAbsent(transfer.id(), k -> new AtomicInteger()).incrementAndGet();
                    sleep(20);
                    if (transfer.beneficiaryRef().startsWith("BENE-CRASH")) {
                        // Stands in for the process dying, or the connection dropping, after the rail got it.
                        throw new IllegalStateException("lost the rail's answer");
                    }
                    return "RR-2X-" + UUID.randomUUID().toString().substring(0, 8) + "-" + refs.incrementAndGet();
                }

                @Override
                public RailLookup status(UUID transferId) {
                    return new RailLookup(RailLookup.State.PROCESSING, null);
                }
            };
        }
    }

    /** Keeps other test classes' @TestConfiguration beans out of these hand-built contexts. */
    static class ExcludeTestConfigurations extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return reader.getAnnotationMetadata().hasAnnotation(TestConfiguration.class.getName());
        }
    }

    static ConfigurableApplicationContext a;
    static ConfigurableApplicationContext b;
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ObjectMapper JSON = new ObjectMapper();
    static JdbcClient db;

    @BeforeAll
    static void startTwoInstances() {
        a = start();
        b = start();
        db = a.getBean(JdbcClient.class);
    }

    @AfterAll
    static void stop() {
        if (a != null) {
            a.close();
        }
        if (b != null) {
            b.close();
        }
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(TransfersApplication.class, CountingRail.class)
                .initializers(ctx -> ctx.getBeanFactory()
                        .registerSingleton("excludeTestConfigurations", new ExcludeTestConfigurations()))
                .run(
                        "--server.port=0",
                        "--transfers.rail.webhook-enabled=false",
                        "--transfers.daily-limit-minor=" + DAILY_LIMIT,
                        // Ticks are driven by the tests so both dispatchers can be fired at the same instant.
                        "--transfers.dispatch-interval-ms=3600000",
                        "--transfers.reconcile.interval-ms=3600000");
    }

    // ---- 1. idempotency ----

    @Test
    void sameIdempotencyKeyOnBothInstancesDebitsOnce() throws Exception {
        String from = newAccount(10_000_000);
        String to = newAccount(0);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = internal(from, to, 100_000);

        List<HttpResponse<String>> responses = concurrently(20, i -> postTransfer(i % 2 == 0 ? a : b, body, key));

        long created = responses.stream().filter(r -> r.statusCode() == 201).count();
        long replayed = responses.stream().filter(r -> r.statusCode() == 200).count();
        assertEquals(1, created, "exactly one request creates; statuses: " + statuses(responses));
        assertEquals(19, replayed, "every other request replays; statuses: " + statuses(responses));
        assertEquals(1, db.sql("select count(*) from transfers where idempotency_key = :k")
                .param("k", key).query(Integer.class).single());
        assertEquals(10_000_000 - 100_000, balance(from));
        assertEquals(100_000, balance(to));
    }

    // ---- 2. daily limit ----

    @Test
    void dailyLimitHoldsAcrossInstances() throws Exception {
        String from = newAccount(100_000_000);
        String to = newAccount(0);
        long amount = 1_000_000; // limit allows exactly 5 of these

        List<HttpResponse<String>> responses = concurrently(20,
                i -> postTransfer(i % 2 == 0 ? a : b, internal(from, to, amount), UUID.randomUUID().toString()));

        long created = responses.stream().filter(r -> r.statusCode() == 201).count();
        long refused = responses.stream().filter(r -> r.body().contains("LIMIT_EXCEEDED")).count();
        assertEquals(5, created, "statuses: " + statuses(responses));
        assertEquals(15, refused, "statuses: " + statuses(responses));
        assertEquals(DAILY_LIMIT, balance(to));
    }

    // ---- 3. dispatch ----

    @Test
    void eachPayoutIsSentToTheRailOnceWithTwoDispatchers() throws Exception {
        String from = newAccount(100_000_000);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            HttpResponse<String> r = postTransfer(i % 2 == 0 ? a : b, payout(from, 10_000), UUID.randomUUID().toString());
            assertEquals(201, r.statusCode(), r.body());
            ids.add(UUID.fromString(JSON.readTree(r.body()).get("id").asText()));
        }

        Dispatcher da = a.getBean(Dispatcher.class);
        Dispatcher dbb = b.getBean(Dispatcher.class);
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline) && !allHaveRailRef(ids)) {
            concurrently(2, i -> {
                (i == 0 ? da : dbb).tick();
                return null;
            });
            Thread.sleep(50);
        }

        assertTrue(allHaveRailRef(ids), "every payout reaches the rail");
        for (UUID id : ids) {
            assertEquals(1, SENDS.get(id).get(), "transfer " + id + " was sent " + SENDS.get(id) + " times");
            assertEquals("DISPATCHED", status(id));
        }
    }

    @Test
    void payoutWhoseSendOutcomeIsUnknownIsNeverResentOrRefunded() throws Exception {
        String from = newAccount(1_000_000);
        Map<String, Object> body = Map.of("type", "PAYOUT", "fromAccountId", from,
                "beneficiaryRef", "BENE-CRASH-" + UUID.randomUUID(), "amountMinor", 100_000, "currency", "INR");
        HttpResponse<String> r = postTransfer(a, body, UUID.randomUUID().toString());
        UUID id = UUID.fromString(JSON.readTree(r.body()).get("id").asText());
        long afterDebit = balance(from);

        for (int round = 0; round < 5; round++) {
            concurrently(2, i -> {
                (i == 0 ? a : b).getBean(Dispatcher.class).tick();
                return null;
            });
            Thread.sleep(100);
        }

        assertEquals(1, SENDS.get(id).get(), "sent once, then left alone");
        assertEquals("DISPATCHED", status(id), "visible as dispatched-without-rail-ref, for reconciliation");
        assertEquals(afterDebit, balance(from), "not refunded: we do not know the partner did not pay");
    }

    // ---- 4. webhooks ----

    @Test
    void duplicateFailedWebhookOnBothInstancesRefundsOnce() throws Exception {
        String from = newAccount(1_000_000);
        HttpResponse<String> r = postTransfer(a, payout(from, 100_000), UUID.randomUUID().toString());
        JsonNode created = JSON.readTree(r.body());
        UUID id = UUID.fromString(created.get("id").asText());
        long debit = created.get("amountMinor").asLong() + created.get("feeMinor").asLong();
        String railRef = dispatchOne(id);

        String webhook = "{\"railRef\":\"" + railRef + "\",\"event\":\"FAILED\"}";
        List<HttpResponse<String>> responses = concurrently(10, i -> postWebhook(i % 2 == 0 ? a : b, webhook));

        assertTrue(responses.stream().allMatch(x -> x.statusCode() == 204), "statuses: " + statuses(responses));
        assertEquals("FAILED", status(id));
        assertEquals(1_000_000, balance(from), "refunded exactly once (debit was " + debit + ")");
        assertEquals(4, db.sql("select count(*) from ledger_entries where transfer_id = :id")
                .param("id", id).query(Integer.class).single());
    }

    @Test
    void failedWebhookAfterCompletedDoesNotRefund() throws Exception {
        String from = newAccount(1_000_000);
        HttpResponse<String> r = postTransfer(a, payout(from, 100_000), UUID.randomUUID().toString());
        UUID id = UUID.fromString(JSON.readTree(r.body()).get("id").asText());
        long afterDebit = balance(from);
        String railRef = dispatchOne(id);

        assertEquals(204, postWebhook(a, "{\"railRef\":\"" + railRef + "\",\"event\":\"COMPLETED\"}").statusCode());
        assertEquals(204, postWebhook(b, "{\"railRef\":\"" + railRef + "\",\"event\":\"FAILED\"}").statusCode());

        assertEquals("COMPLETED", status(id));
        assertEquals(afterDebit, balance(from));
        assertEquals(2, db.sql("select count(*) from partner_events where transfer_id = :id")
                .param("id", id).query(Integer.class).single(), "both events are kept for audit");
    }

    // ---- helpers ----

    private String dispatchOne(UUID id) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            a.getBean(Dispatcher.class).tick();
            String ref = db.sql("select rail_ref from transfers where id = :id").param("id", id)
                    .query(String.class).optional().orElse(null);
            if (ref != null) {
                return ref;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("transfer " + id + " never reached the rail");
    }

    private static boolean allHaveRailRef(List<UUID> ids) {
        return db.sql("select count(*) from transfers where id in (:ids) and rail_ref is not null")
                .param("ids", ids).query(Integer.class).single() == ids.size();
    }

    private static String status(UUID id) {
        return db.sql("select status from transfers where id = :id").param("id", id).query(String.class).single();
    }

    private static <T> List<T> concurrently(int n, ThrowingFn<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit((Callable<T>) () -> {
                    go.await();
                    return task.apply(idx);
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    interface ThrowingFn<T> {
        T apply(int i) throws Exception;
    }

    private static String newAccount(long openingBalanceMinor) throws Exception {
        String body = JSON.writeValueAsString(Map.of("userId", "u-" + UUID.randomUUID(), "currency", "INR",
                "openingBalanceMinor", openingBalanceMinor));
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri(a, "/accounts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("id").asText();
    }

    private static long balance(String accountId) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri(b, "/accounts/" + accountId)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(r.body()).get("balanceMinor").asLong();
    }

    private static HttpResponse<String> postTransfer(ConfigurableApplicationContext instance, Map<String, Object> body,
            String key) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri(instance, "/transfers"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postWebhook(ConfigurableApplicationContext instance, String body)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri(instance, "/internal/rail/webhook"))
                .header("Content-Type", "application/json")
                .header("X-Rail-Signature", WebhookSignature.sign(SECRET, body))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(ConfigurableApplicationContext instance, String path) {
        int port = ((WebServerApplicationContext) instance).getWebServer().getPort();
        return URI.create("http://localhost:" + port + path);
    }

    private static List<Integer> statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(HttpResponse::statusCode).toList();
    }

    private static Map<String, Object> internal(String from, String to, long amountMinor) {
        return Map.of("type", "INTERNAL", "fromAccountId", from, "toAccountId", to, "amountMinor", amountMinor,
                "currency", "INR");
    }

    private static Map<String, Object> payout(String from, long amountMinor) {
        return Map.of("type", "PAYOUT", "fromAccountId", from, "beneficiaryRef", "BENE-" + UUID.randomUUID(),
                "amountMinor", amountMinor, "currency", "INR");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

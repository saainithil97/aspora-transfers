package com.aspora.interview.transfers.rail;

import com.aspora.interview.transfers.config.AppProperties;
import com.aspora.interview.transfers.domain.Transfer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Stand-in for a real payout rail. Takes RAIL_LATENCY_MS to answer, refuses with probability
 * RAIL_FAILURE_RATE, and otherwise returns a reference and later calls our webhook with the outcome,
 * the way a real partner would. It loses RAIL_WEBHOOK_DROP_RATE of those webhooks, as the real one does,
 * and answers status lookups (failing RAIL_LOOKUP_FAILURE_RATE of them).
 *
 * <p>The partner's books are the fake_rail_payments table, so every instance of the service sees the same
 * partner. A send is deduplicated on our transfer id, which is what we need the real partner to do too.
 */
public class FakeRailClient implements RailClient {

    private static final Logger log = LoggerFactory.getLogger(FakeRailClient.class);

    private final AppProperties.Rail config;
    private final JdbcClient db;
    private final String webhookUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService callbacks = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rail-callback");
        t.setDaemon(true);
        return t;
    });

    public FakeRailClient(AppProperties.Rail config, JdbcClient db, String selfBaseUrl) {
        this.config = config;
        this.db = db;
        this.webhookUrl = selfBaseUrl + "/internal/rail/webhook";
    }

    @Override
    public String send(Transfer transfer) {
        sleep(config.latencyMs());
        if (ThreadLocalRandom.current().nextDouble() < config.failureRate()) {
            throw new RailUnavailableException("rail refused transfer " + transfer.id());
        }
        String event = ThreadLocalRandom.current().nextDouble() < 0.9 ? "COMPLETED" : "FAILED";
        long delayMs = 1000 + ThreadLocalRandom.current().nextLong(1000);
        String fresh = "RR-" + Long.toHexString(ThreadLocalRandom.current().nextLong()).substring(0, 8).toUpperCase();
        db.sql("""
                insert into fake_rail_payments (transfer_id, rail_ref, outcome, settles_at)
                values (:id, :ref, :outcome, now() + make_interval(secs => :delay / 1000.0))
                on conflict (transfer_id) do nothing
                """)
                .param("id", transfer.id())
                .param("ref", fresh)
                .param("outcome", event)
                .param("delay", delayMs)
                .update();
        // A repeat send of a transfer we already have gets the original payment back, not a second one.
        String railRef = db.sql("select rail_ref from fake_rail_payments where transfer_id = :id")
                .param("id", transfer.id())
                .query(String.class)
                .single();
        if (!railRef.equals(fresh)) {
            log.info("rail already has transfer {} as {}; not paying it twice", transfer.id(), railRef);
            return railRef;
        }
        if (config.webhookEnabled()) {
            if (ThreadLocalRandom.current().nextDouble() < config.webhookDropRate()) {
                log.info("rail will drop the {} webhook for {}", event, railRef);
            } else {
                callbacks.schedule(() -> postWebhook(railRef, event), delayMs, TimeUnit.MILLISECONDS);
            }
        }
        return railRef;
    }

    @Override
    public RailLookup status(UUID transferId) {
        sleep(config.latencyMs());
        if (ThreadLocalRandom.current().nextDouble() < config.lookupFailureRate()) {
            throw new RailUnavailableException("rail status lookup for " + transferId + " timed out");
        }
        return db.sql("""
                select rail_ref, case when settles_at <= now() then outcome else 'PROCESSING' end as state
                from fake_rail_payments where transfer_id = :id
                """)
                .param("id", transferId)
                .query((rs, i) -> new RailLookup(RailLookup.State.valueOf(rs.getString("state")), rs.getString("rail_ref")))
                .optional()
                .orElse(new RailLookup(RailLookup.State.NOT_FOUND, null));
    }

    private void postWebhook(String railRef, String event) {
        String body = "{\"railRef\":\"" + railRef + "\",\"event\":\"" + event + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .header("X-Rail-Signature", WebhookSignature.sign(config.webhookSecret(), body))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            log.info("rail webhook {} for {} -> HTTP {}", event, railRef, response.statusCode());
        } catch (Exception e) {
            log.warn("rail webhook {} for {} could not be delivered: {}", event, railRef, e.toString());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

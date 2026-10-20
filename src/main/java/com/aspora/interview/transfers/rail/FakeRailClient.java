package com.aspora.interview.transfers.rail;

import com.aspora.interview.transfers.config.AppProperties;
import com.aspora.interview.transfers.domain.Transfer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stand-in for a real payout rail. Takes RAIL_LATENCY_MS to answer, refuses with probability
 * RAIL_FAILURE_RATE, and otherwise returns a reference and later calls our webhook with the outcome,
 * the way a real partner would.
 */
public class FakeRailClient implements RailClient {

    private static final Logger log = LoggerFactory.getLogger(FakeRailClient.class);

    private final AppProperties.Rail config;
    private final String webhookUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService callbacks = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rail-callback");
        t.setDaemon(true);
        return t;
    });

    public FakeRailClient(AppProperties.Rail config, String selfBaseUrl) {
        this.config = config;
        this.webhookUrl = selfBaseUrl + "/internal/rail/webhook";
    }

    @Override
    public String send(Transfer transfer) {
        sleep(config.latencyMs());
        if (ThreadLocalRandom.current().nextDouble() < config.failureRate()) {
            throw new RailUnavailableException("rail refused transfer " + transfer.id());
        }
        String railRef = "RR-" + Long.toHexString(ThreadLocalRandom.current().nextLong()).substring(0, 8).toUpperCase();
        if (config.webhookEnabled()) {
            String event = ThreadLocalRandom.current().nextDouble() < 0.9 ? "COMPLETED" : "FAILED";
            long delayMs = 1000 + ThreadLocalRandom.current().nextLong(1000);
            callbacks.schedule(() -> postWebhook(railRef, event), delayMs, TimeUnit.MILLISECONDS);
        }
        return railRef;
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

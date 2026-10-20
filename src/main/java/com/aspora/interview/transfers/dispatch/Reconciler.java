package com.aspora.interview.transfers.dispatch;

import com.aspora.interview.transfers.config.AppProperties;
import com.aspora.interview.transfers.domain.TransferService;
import com.aspora.interview.transfers.domain.TransferStatus;
import com.aspora.interview.transfers.rail.RailClient;
import com.aspora.interview.transfers.rail.RailLookup;
import com.aspora.interview.transfers.store.PartnerEventStore;
import com.aspora.interview.transfers.store.TransferStore;
import com.aspora.interview.transfers.store.TransferStore.ReconcileCandidate;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settles DISPATCHED transfers whose webhook has not arrived by asking the rail. Runs on every instance; rows
 * are claimed with SKIP LOCKED and a next_check_at lease, so each due transfer is looked at by one instance.
 *
 * <ul>
 *   <li>COMPLETED / FAILED: applied exactly as a webhook would be (FAILED refunds).</li>
 *   <li>PROCESSING: asked again later, until it has been processing for too long.</li>
 *   <li>NOT_FOUND, and we never got a rail_ref: the send never landed, so it goes back to PENDING and is sent
 *       again, up to max-resends. Only believed after the grace period, so a send still in flight can land.</li>
 *   <li>NOT_FOUND, but the rail once gave us a rail_ref: the rail contradicts itself; a human decides.</li>
 *   <li>The lookup itself fails: retried with backoff, then a human decides. Never refunded on a guess.</li>
 * </ul>
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);
    private static final int BATCH = 50;
    private static final int WORKERS = 8;

    private final TransferStore transfers;
    private final TransferService transferService;
    private final PartnerEventStore partnerEvents;
    private final RailClient rail;
    private final TransactionTemplate tx;
    private final AppProperties.Reconcile config;
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS, r -> {
        Thread t = new Thread(r, "reconcile-worker");
        t.setDaemon(true);
        return t;
    });

    public Reconciler(TransferStore transfers, TransferService transferService, PartnerEventStore partnerEvents,
            RailClient rail, TransactionTemplate tx, AppProperties props) {
        this.transfers = transfers;
        this.transferService = transferService;
        this.partnerEvents = partnerEvents;
        this.rail = rail;
        this.tx = tx;
        this.config = props.reconcile();
    }

    @Scheduled(initialDelayString = "${transfers.reconcile.interval-ms}",
            fixedDelayString = "${transfers.reconcile.interval-ms}")
    public void tick() {
        // The claim commits on its own, so no database lock is held while we wait on the rail. Lookups run in
        // parallel so a backlog (say, after a rail outage) drains in minutes, and the tick waits for them so a
        // batch is finished well inside its lease.
        List<Callable<Void>> lookups = new ArrayList<>();
        for (ReconcileCandidate candidate : transfers.claimDueForReconcile(BATCH, config.retryMs())) {
            lookups.add(() -> {
                try {
                    reconcile(candidate);
                } catch (RuntimeException e) {
                    // Leaves it DISPATCHED; next_check_at was already pushed forward, so it is tried again later.
                    log.error("reconciling transfer {} blew up", candidate.id(), e);
                }
                return null;
            });
        }
        try {
            workers.invokeAll(lookups);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    private void reconcile(ReconcileCandidate t) {
        RailLookup answer;
        try {
            answer = rail.status(t.id());
        } catch (RuntimeException e) {
            if (t.checkAttempts() >= config.maxAttempts()) {
                review(t, "RAIL_STATUS_UNKNOWN");
            } else {
                log.warn("status lookup for transfer {} failed (attempt {}): {}", t.id(), t.checkAttempts(),
                        e.getMessage());
            }
            return;
        }

        switch (answer.state()) {
            case COMPLETED -> settle(t, answer, TransferStatus.COMPLETED);
            case FAILED -> settle(t, answer, TransferStatus.FAILED);
            case PROCESSING -> {
                if (Duration.between(t.dispatchedAt(), Instant.now()).toMillis() > config.maxProcessingMs()) {
                    review(t, "RAIL_STILL_PROCESSING");
                }
            }
            case NOT_FOUND -> {
                record(t, answer);
                if (t.railRef() != null) {
                    review(t, "RAIL_LOST_ACCEPTED_TRANSFER");
                } else if (t.dispatchAttempts() >= config.maxResends()) {
                    review(t, "RAIL_NOT_FOUND_AFTER_RESENDS");
                } else if (transferService.requeueForResend(t.id())) {
                    log.warn("rail has no record of transfer {}; queued to send again (send {} of {})", t.id(),
                            t.dispatchAttempts() + 1, config.maxResends());
                }
            }
        }
    }

    private void settle(ReconcileCandidate t, RailLookup answer, TransferStatus outcome) {
        tx.executeWithoutResult(status -> {
            // The rail's answer is stored with the change it causes: this row is what a refund is justified by.
            record(t, answer);
            if (t.railRef() == null && answer.railRef() != null) {
                transfers.setRailRef(t.id(), answer.railRef());
            }
            TransferService.Settlement result = transferService.settle(t.id(), outcome);
            log.info("status lookup settled transfer {} as {} ({})", t.id(), outcome, result);
        });
    }

    private void record(ReconcileCandidate t, RailLookup answer) {
        String payload = "{\"source\":\"status_lookup\",\"state\":\"" + answer.state() + "\""
                + (answer.railRef() == null ? "" : ",\"railRef\":\"" + answer.railRef() + "\"") + "}";
        partnerEvents.insert(t.id(), "LOOKUP_" + answer.state(), answer.railRef(), payload);
    }

    private void review(ReconcileCandidate t, String reason) {
        if (transferService.markNeedsReview(t.id(), reason)) {
            log.error("transfer {} needs review: {}", t.id(), reason);
        }
    }
}

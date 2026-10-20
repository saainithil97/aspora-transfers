package com.aspora.interview.transfers.dispatch;

import com.aspora.interview.transfers.domain.Transfer;
import com.aspora.interview.transfers.domain.TransferService;
import com.aspora.interview.transfers.rail.RailClient;
import com.aspora.interview.transfers.rail.RailUnavailableException;
import com.aspora.interview.transfers.store.TransferStore;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Picks up PENDING payouts every DISPATCH_INTERVAL_MS and hands them to the rail on a small worker pool.
 * Every instance runs one of these. Who sends a transfer is decided in the database by
 * {@link TransferService#claimForDispatch}; {@code inFlight} only saves this instance from queueing the
 * same id twice and is not relied on for correctness.
 */
@Component
public class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
    private static final int BATCH = 20;

    private final TransferStore transfers;
    private final TransferService transferService;
    private final RailClient rail;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "dispatch-worker");
        t.setDaemon(true);
        return t;
    });

    public Dispatcher(TransferStore transfers, TransferService transferService, RailClient rail) {
        this.transfers = transfers;
        this.transferService = transferService;
        this.rail = rail;
    }

    @Scheduled(initialDelayString = "${transfers.dispatch-interval-ms}",
            fixedDelayString = "${transfers.dispatch-interval-ms}")
    public void tick() {
        List<UUID> pending = transfers.pendingIds(BATCH);
        for (UUID id : pending) {
            if (inFlight.add(id)) {
                workers.submit(() -> dispatch(id));
            }
        }
    }

    private void dispatch(UUID id) {
        try {
            Optional<Transfer> claimed = transferService.claimForDispatch(id);
            if (claimed.isEmpty()) {
                return; // another dispatcher got it
            }
            Transfer transfer = claimed.get();
            try {
                String railRef = rail.send(transfer);
                transfers.setRailRef(id, railRef);
                log.info("dispatched transfer {} as {}", id, railRef);
            } catch (RailUnavailableException e) {
                transferService.fail(transfer, RailUnavailableException.CODE);
                log.warn("transfer {} failed at the rail: {}", id, e.getMessage());
            }
        } catch (RuntimeException e) {
            // Claimed but the outcome of the send is unknown. Deliberately not retried here: resending
            // without knowing whether the rail took it risks paying twice. Needs a status lookup to settle.
            log.error("dispatch of transfer {} blew up; left DISPATCHED for reconciliation", id, e);
        } finally {
            inFlight.remove(id);
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}

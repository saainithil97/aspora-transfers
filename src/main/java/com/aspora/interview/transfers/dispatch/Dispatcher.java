package com.aspora.interview.transfers.dispatch;

import com.aspora.interview.transfers.domain.Transfer;
import com.aspora.interview.transfers.domain.TransferService;
import com.aspora.interview.transfers.rail.RailClient;
import com.aspora.interview.transfers.rail.RailUnavailableException;
import com.aspora.interview.transfers.store.TransferStore;
import jakarta.annotation.PreDestroy;
import java.util.List;
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
 * Transfers currently being worked on are remembered so a tick does not pick them up a second time.
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
            Transfer transfer = transferService.markDispatched(id);
            try {
                String railRef = rail.send(transfer);
                transfers.setRailRef(id, railRef);
                log.info("dispatched transfer {} as {}", id, railRef);
            } catch (RailUnavailableException e) {
                transferService.fail(transfer, RailUnavailableException.CODE);
                log.warn("transfer {} failed at the rail: {}", id, e.getMessage());
            }
        } catch (RuntimeException e) {
            log.error("dispatch of transfer {} blew up", id, e);
        } finally {
            inFlight.remove(id);
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}

package com.aspora.interview.transfers.rail;

import com.aspora.interview.transfers.domain.ApiException;
import com.aspora.interview.transfers.domain.ErrorCode;
import com.aspora.interview.transfers.domain.Transfer;
import com.aspora.interview.transfers.domain.TransferService;
import com.aspora.interview.transfers.domain.TransferStatus;
import com.aspora.interview.transfers.store.PartnerEventStore;
import com.aspora.interview.transfers.store.TransferStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Applies a rail outcome (COMPLETED / FAILED) to the transfer it belongs to. */
@Component
public class RailWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(RailWebhookHandler.class);

    private final TransferStore transfers;
    private final PartnerEventStore partnerEvents;
    private final TransferService transferService;
    private final TransactionTemplate tx;

    public RailWebhookHandler(TransferStore transfers, PartnerEventStore partnerEvents,
            TransferService transferService, TransactionTemplate tx) {
        this.transfers = transfers;
        this.partnerEvents = partnerEvents;
        this.transferService = transferService;
        this.tx = tx;
    }

    public void handle(String railRef, String event, String rawPayload) {
        TransferStatus outcome = switch (event == null ? "" : event) {
            case "COMPLETED" -> TransferStatus.COMPLETED;
            case "FAILED" -> TransferStatus.FAILED;
            default -> throw new ApiException(ErrorCode.VALIDATION, "event must be COMPLETED or FAILED");
        };
        if (railRef == null || railRef.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION, "railRef is required");
        }
        tx.executeWithoutResult(status -> {
            Transfer transfer = transfers.findByRailRef(railRef)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "no transfer with rail_ref " + railRef));
            partnerEvents.insert(transfer.id(), event, railRef, rawPayload);
            // Rails redeliver, a status lookup may have settled it already, and with two instances two copies
            // of one webhook can be in here at the same moment. settle() lets exactly one of them apply it.
            switch (transferService.settle(transfer.id(), outcome)) {
                case APPLIED -> log.info("rail {} for transfer {} ({})", event, transfer.id(), railRef);
                case ALREADY_APPLIED -> log.info("duplicate rail {} for transfer {} ({}), already applied",
                        event, transfer.id(), railRef);
                // The rail has told us two different things. Money is left as it is; the event is kept above.
                case CONFLICT -> log.error("rail {} for transfer {} ({}) conflicts with its current status; "
                        + "not applied, needs review", event, transfer.id(), railRef);
            }
        });
    }
}

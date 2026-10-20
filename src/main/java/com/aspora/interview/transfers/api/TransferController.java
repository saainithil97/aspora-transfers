package com.aspora.interview.transfers.api;

import com.aspora.interview.transfers.domain.ApiException;
import com.aspora.interview.transfers.domain.ErrorCode;
import com.aspora.interview.transfers.domain.NewTransfer;
import com.aspora.interview.transfers.domain.Transfer;
import com.aspora.interview.transfers.domain.TransferService;
import com.aspora.interview.transfers.domain.TransferStatus;
import com.aspora.interview.transfers.store.TransferStore;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

    private final TransferService service;
    private final TransferStore transfers;

    public TransferController(TransferService service, TransferStore transfers) {
        this.service = service;
        this.transfers = transfers;
    }

    @PostMapping("/transfers")
    public ResponseEntity<Transfer> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NewTransfer body) {
        if (idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION, "Idempotency-Key header must not be blank");
        }
        TransferService.CreateResult result = service.create(idempotencyKey, body);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.transfer());
    }

    /**
     * Ops view, e.g. ?status=NEEDS_REVIEW for the transfers we could not settle automatically, oldest first.
     * Internal like the webhook: not meant to be reachable from outside, and has no auth of its own yet.
     */
    @GetMapping("/internal/transfers")
    public List<Transfer> byStatus(@RequestParam TransferStatus status,
            @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 500) {
            throw new ApiException(ErrorCode.VALIDATION, "limit must be between 1 and 500");
        }
        return transfers.listByStatus(status, limit);
    }

    @GetMapping("/transfers/{id}")
    public Transfer get(@PathVariable UUID id) {
        return transfers.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "no transfer " + id));
    }
}

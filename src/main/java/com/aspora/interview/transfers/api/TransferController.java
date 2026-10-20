package com.aspora.interview.transfers.api;

import com.aspora.interview.transfers.domain.ApiException;
import com.aspora.interview.transfers.domain.ErrorCode;
import com.aspora.interview.transfers.domain.NewTransfer;
import com.aspora.interview.transfers.domain.Transfer;
import com.aspora.interview.transfers.domain.TransferService;
import com.aspora.interview.transfers.store.TransferStore;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    @GetMapping("/transfers/{id}")
    public Transfer get(@PathVariable UUID id) {
        return transfers.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "no transfer " + id));
    }
}

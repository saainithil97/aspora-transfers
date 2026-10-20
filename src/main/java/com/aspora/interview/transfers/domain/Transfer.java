package com.aspora.interview.transfers.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.UUID;

public record Transfer(
        UUID id,
        @JsonIgnore String idempotencyKey,
        @JsonIgnore String requestHash,
        TransferType type,
        TransferStatus status,
        UUID fromAccountId,
        UUID toAccountId,
        String beneficiaryRef,
        long amountMinor,
        long feeMinor,
        String currency,
        String railRef,
        String failureReason,
        String reviewReason,
        Instant createdAt,
        Instant updatedAt) {

    /** Total taken from the source account: the amount plus the fee. */
    public long debitMinor() {
        return amountMinor + feeMinor;
    }
}

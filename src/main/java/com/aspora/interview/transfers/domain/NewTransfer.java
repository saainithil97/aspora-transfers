package com.aspora.interview.transfers.domain;

import java.util.UUID;

/** The body of POST /transfers. Boxed types so missing fields can be reported as validation errors. */
public record NewTransfer(
        TransferType type,
        UUID fromAccountId,
        UUID toAccountId,
        String beneficiaryRef,
        Long amountMinor,
        String currency) {
}

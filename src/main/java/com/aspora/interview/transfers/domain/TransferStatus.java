package com.aspora.interview.transfers.domain;

public enum TransferStatus {
    PENDING,
    DISPATCHED,
    COMPLETED,
    FAILED,
    /** We could not establish what the rail did with it. Money stays debited until a human or the rail settles it. */
    NEEDS_REVIEW
}

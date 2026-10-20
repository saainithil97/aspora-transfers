package com.aspora.interview.transfers.domain;

import java.time.Instant;
import java.util.UUID;

/** One side of a money movement. accountId is null for the external (rail) side of a PAYOUT. */
public record LedgerEntry(
        long id, UUID transferId, UUID accountId, Direction direction, long amountMinor, Instant createdAt) {

    public enum Direction {
        DEBIT,
        CREDIT
    }
}

package com.aspora.interview.transfers.domain;

import static com.aspora.interview.transfers.domain.TransferStatus.COMPLETED;
import static com.aspora.interview.transfers.domain.TransferStatus.DISPATCHED;
import static com.aspora.interview.transfers.domain.TransferStatus.FAILED;
import static com.aspora.interview.transfers.domain.TransferStatus.PENDING;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The transfer status machine. Terminal states have no exits. */
public final class TransferState {

    private static final Map<TransferStatus, Set<TransferStatus>> NEXT = Map.of(
            PENDING, EnumSet.of(DISPATCHED, FAILED),
            DISPATCHED, EnumSet.of(COMPLETED, FAILED),
            COMPLETED, EnumSet.noneOf(TransferStatus.class),
            FAILED, EnumSet.noneOf(TransferStatus.class));

    private TransferState() {
    }

    public static boolean canTransition(TransferStatus from, TransferStatus to) {
        return NEXT.get(from).contains(to);
    }

    public static boolean isTerminal(TransferStatus status) {
        return NEXT.get(status).isEmpty();
    }

    public static void assertTransition(TransferStatus from, TransferStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidTransitionException(from, to);
        }
    }
}

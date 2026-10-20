package com.aspora.interview.transfers.domain;

import static com.aspora.interview.transfers.domain.TransferStatus.COMPLETED;
import static com.aspora.interview.transfers.domain.TransferStatus.DISPATCHED;
import static com.aspora.interview.transfers.domain.TransferStatus.FAILED;
import static com.aspora.interview.transfers.domain.TransferStatus.NEEDS_REVIEW;
import static com.aspora.interview.transfers.domain.TransferStatus.PENDING;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TransferStateTest {

    @Test
    void legalTransitions() {
        assertTrue(TransferState.canTransition(PENDING, DISPATCHED));
        assertTrue(TransferState.canTransition(PENDING, FAILED));
        assertTrue(TransferState.canTransition(DISPATCHED, COMPLETED));
        assertTrue(TransferState.canTransition(DISPATCHED, FAILED));
        assertTrue(TransferState.canTransition(DISPATCHED, PENDING));
        assertTrue(TransferState.canTransition(DISPATCHED, NEEDS_REVIEW));
        assertTrue(TransferState.canTransition(NEEDS_REVIEW, COMPLETED));
        assertTrue(TransferState.canTransition(NEEDS_REVIEW, FAILED));
    }

    @Test
    void illegalTransitions() {
        assertFalse(TransferState.canTransition(PENDING, COMPLETED));
        assertFalse(TransferState.canTransition(PENDING, NEEDS_REVIEW));
        assertFalse(TransferState.canTransition(NEEDS_REVIEW, PENDING));
        assertFalse(TransferState.canTransition(NEEDS_REVIEW, DISPATCHED));
        assertFalse(TransferState.canTransition(PENDING, PENDING));
        for (TransferStatus terminal : new TransferStatus[] {COMPLETED, FAILED}) {
            for (TransferStatus to : TransferStatus.values()) {
                assertFalse(TransferState.canTransition(terminal, to), terminal + " -> " + to);
            }
        }
    }

    @Test
    void terminalStates() {
        assertTrue(TransferState.isTerminal(COMPLETED));
        assertTrue(TransferState.isTerminal(FAILED));
        assertFalse(TransferState.isTerminal(PENDING));
        assertFalse(TransferState.isTerminal(DISPATCHED));
        assertFalse(TransferState.isTerminal(NEEDS_REVIEW));
    }

    @Test
    void assertTransitionThrowsTypedError() {
        assertDoesNotThrow(() -> TransferState.assertTransition(PENDING, DISPATCHED));
        InvalidTransitionException e = assertThrows(InvalidTransitionException.class,
                () -> TransferState.assertTransition(COMPLETED, FAILED));
        assertEquals(ErrorCode.INVALID_TRANSITION, e.code());
    }
}

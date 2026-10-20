package com.aspora.interview.transfers.domain;

import static com.aspora.interview.transfers.domain.TransferStatus.COMPLETED;
import static com.aspora.interview.transfers.domain.TransferStatus.DISPATCHED;
import static com.aspora.interview.transfers.domain.TransferStatus.FAILED;
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
    }

    @Test
    void illegalTransitions() {
        assertFalse(TransferState.canTransition(PENDING, COMPLETED));
        assertFalse(TransferState.canTransition(DISPATCHED, PENDING));
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
    }

    @Test
    void assertTransitionThrowsTypedError() {
        assertDoesNotThrow(() -> TransferState.assertTransition(PENDING, DISPATCHED));
        InvalidTransitionException e = assertThrows(InvalidTransitionException.class,
                () -> TransferState.assertTransition(COMPLETED, FAILED));
        assertEquals(ErrorCode.INVALID_TRANSITION, e.code());
    }
}

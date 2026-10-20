package com.aspora.interview.transfers.domain;

public class InvalidTransitionException extends ApiException {

    public InvalidTransitionException(TransferStatus from, TransferStatus to) {
        super(ErrorCode.INVALID_TRANSITION, "cannot move transfer from " + from + " to " + to);
    }
}

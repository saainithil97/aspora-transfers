package com.aspora.interview.transfers.domain;

public enum ErrorCode {
    VALIDATION(400),
    NOT_FOUND(404),
    INSUFFICIENT_FUNDS(422),
    LIMIT_EXCEEDED(422),
    IDEMPOTENCY_MISMATCH(409),
    INVALID_TRANSITION(409),
    UNAUTHORIZED(401);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}

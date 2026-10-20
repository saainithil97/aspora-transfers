package com.aspora.interview.transfers.domain;

/** A business error that maps 1:1 onto an HTTP error response. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}

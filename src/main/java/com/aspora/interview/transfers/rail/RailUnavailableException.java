package com.aspora.interview.transfers.rail;

public class RailUnavailableException extends RuntimeException {

    public static final String CODE = "RAIL_UNAVAILABLE";

    public RailUnavailableException(String message) {
        super(message);
    }
}

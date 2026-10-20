package com.aspora.interview.transfers.rail;

/** The rail's answer to "what happened to this transfer?". railRef is null when the rail has no record of it. */
public record RailLookup(State state, String railRef) {

    public enum State {
        COMPLETED,
        FAILED,
        /** The rail has it and has not decided yet. */
        PROCESSING,
        /** The rail has no record of a payment with our reference. */
        NOT_FOUND
    }
}

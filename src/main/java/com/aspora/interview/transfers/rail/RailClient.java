package com.aspora.interview.transfers.rail;

import com.aspora.interview.transfers.domain.Transfer;

/** The payout rail. Accepts a transfer for sending and returns the rail's reference for it. */
public interface RailClient {

    String send(Transfer transfer) throws RailUnavailableException;
}

package com.aspora.interview.transfers.rail;

import com.aspora.interview.transfers.domain.Transfer;
import java.util.UUID;

/**
 * The payout rail. Accepts a transfer for sending and returns the rail's reference for it.
 * Implementations must pass {@code transfer.id()} to the partner as our reference for the payment, so it can
 * be looked up (and deduplicated, if the partner supports it) when we do not know whether a send landed.
 */
public interface RailClient {

    String send(Transfer transfer) throws RailUnavailableException;

    /**
     * Asks the rail what became of the payment we sent with reference {@code transferId}.
     *
     * @throws RailUnavailableException when the rail cannot be asked right now; the answer is unknown, not NOT_FOUND
     */
    RailLookup status(UUID transferId) throws RailUnavailableException;
}

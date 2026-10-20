package com.aspora.interview.transfers.fees;

/** Computes the fee, in minor units, charged on top of a transfer amount. */
public interface FeeRule {

    long feeFor(long amountMinor);
}

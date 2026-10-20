package com.aspora.interview.transfers.fees;

/** rule_type FLAT: params {"amountMinor": N}. */
public record FlatFeeRule(long amountMinor) implements FeeRule {

    @Override
    public long feeFor(long ignoredAmountMinor) {
        return amountMinor;
    }
}

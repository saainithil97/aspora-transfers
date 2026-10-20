package com.aspora.interview.transfers.fees;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FlatFeeRuleTest {

    @Test
    void chargesTheSameFeeWhateverTheAmount() {
        FeeRule rule = new FlatFeeRule(500);
        assertEquals(500, rule.feeFor(1));
        assertEquals(500, rule.feeFor(100_000));
        assertEquals(500, rule.feeFor(10_000_000));
    }

    @Test
    void zeroFlatFeeIsAllowed() {
        assertEquals(0, new FlatFeeRule(0).feeFor(250_000));
    }
}

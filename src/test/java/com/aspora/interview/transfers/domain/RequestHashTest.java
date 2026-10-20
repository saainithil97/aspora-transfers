package com.aspora.interview.transfers.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestHashTest {

    private static final UUID FROM = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TO = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void canonicalFormSortsKeysAndDropsNulls() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("currency", "INR");
        a.put("amountMinor", 100L);
        a.put("toAccountId", null);
        Map<String, Object> b = new HashMap<>();
        b.put("amountMinor", 100L);
        b.put("currency", "INR");

        assertEquals("{\"amountMinor\":100,\"currency\":\"INR\"}", RequestHash.canonical(a));
        assertEquals(RequestHash.canonical(a), RequestHash.canonical(b));
    }

    @Test
    void sameRequestSameHash() {
        NewTransfer x = new NewTransfer(TransferType.INTERNAL, FROM, TO, null, 100L, "INR");
        NewTransfer y = new NewTransfer(TransferType.INTERNAL, FROM, TO, null, 100L, "INR");
        assertEquals(RequestHash.of(x), RequestHash.of(y));
        assertEquals(64, RequestHash.of(x).length());
    }

    @Test
    void differentAmountDifferentHash() {
        NewTransfer x = new NewTransfer(TransferType.INTERNAL, FROM, TO, null, 100L, "INR");
        NewTransfer y = new NewTransfer(TransferType.INTERNAL, FROM, TO, null, 101L, "INR");
        assertNotEquals(RequestHash.of(x), RequestHash.of(y));
    }

    @Test
    void differentTypeDifferentHash() {
        NewTransfer x = new NewTransfer(TransferType.INTERNAL, FROM, TO, null, 100L, "INR");
        NewTransfer y = new NewTransfer(TransferType.PAYOUT, FROM, null, "BENE-1", 100L, "INR");
        assertNotEquals(RequestHash.of(x), RequestHash.of(y));
    }
}

package com.aspora.interview.transfers.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Fingerprint of a create-transfer request, stored next to the idempotency key so a replay with a
 * different body can be told apart from a genuine retry.
 */
public final class RequestHash {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RequestHash() {
    }

    public static String of(NewTransfer request) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("type", request.type() == null ? null : request.type().name());
        fields.put("fromAccountId", request.fromAccountId() == null ? null : request.fromAccountId().toString());
        fields.put("toAccountId", request.toAccountId() == null ? null : request.toAccountId().toString());
        fields.put("beneficiaryRef", request.beneficiaryRef());
        fields.put("amountMinor", request.amountMinor());
        fields.put("currency", request.currency());
        return sha256(canonical(fields));
    }

    /** Sorted keys, nulls dropped, compact JSON. Field order and absent-vs-null never change the result. */
    static String canonical(Map<String, ?> fields) {
        Map<String, Object> sorted = new TreeMap<>();
        fields.forEach((k, v) -> {
            if (v != null) {
                sorted.put(k, v);
            }
        });
        try {
            return JSON.writeValueAsString(sorted);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

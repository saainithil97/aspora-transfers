package com.aspora.interview.transfers.domain;

import java.time.Instant;
import java.util.UUID;

public record Account(UUID id, String userId, String currency, long balanceMinor, Instant createdAt) {
}

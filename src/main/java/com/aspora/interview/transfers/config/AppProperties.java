package com.aspora.interview.transfers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "transfers")
public record AppProperties(long dailyLimitMinor, long dispatchIntervalMs, Rail rail) {

    public record Rail(double failureRate, long latencyMs, String webhookSecret, boolean webhookEnabled) {
    }
}

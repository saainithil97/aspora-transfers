package com.aspora.interview.transfers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "transfers")
public record AppProperties(long dailyLimitMinor, long dispatchIntervalMs, Rail rail, Reconcile reconcile) {

    public record Rail(double failureRate, long latencyMs, String webhookSecret, boolean webhookEnabled,
            double webhookDropRate, double lookupFailureRate) {
    }

    /**
     * graceMs: how long after dispatch we wait for the webhook before asking, and so how long a send that is
     * still on its way to the rail has to land before NOT_FOUND is believed. retryMs: base of the backoff between
     * lookups. maxAttempts: lookups we could not get an answer from before a human is asked. maxResends: sends
     * of one transfer the rail never received before a human is asked. maxProcessingMs: how long the rail may
     * say PROCESSING before a human is asked.
     */
    public record Reconcile(long intervalMs, long graceMs, long retryMs, int maxAttempts, int maxResends,
            long maxProcessingMs) {
    }
}

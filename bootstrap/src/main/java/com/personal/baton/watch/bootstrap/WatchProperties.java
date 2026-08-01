package com.personal.baton.watch.bootstrap;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("watch")
public record WatchProperties(
        String apiToken,
        Duration pollInterval,
        Duration maintenanceInterval,
        Duration leaseDuration,
        Duration checkInterval,
        Duration internalFailureRetryInterval,
        Duration staleAfter,
        Duration retention,
        int checkBatchSize,
        int maintenanceBatchSize,
        Http http) {

    public WatchProperties {
        apiToken = requireToken(apiToken);
        pollInterval = requirePositive(pollInterval, "pollInterval");
        maintenanceInterval = requirePositive(maintenanceInterval, "maintenanceInterval");
        leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        checkInterval = requirePositive(checkInterval, "checkInterval");
        internalFailureRetryInterval = requirePositive(
                internalFailureRetryInterval, "internalFailureRetryInterval");
        staleAfter = requirePositive(staleAfter, "staleAfter");
        retention = requirePositive(retention, "retention");
        if (checkBatchSize <= 0 || maintenanceBatchSize <= 0) {
            throw new IllegalArgumentException("worker batch sizes must be positive");
        }
        Objects.requireNonNull(http, "http");
        Duration maximumBatchRuntime;
        try {
            maximumBatchRuntime = http.totalTimeout().multipliedBy(checkBatchSize);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("check batch runtime is too large");
        }
        if (leaseDuration.compareTo(maximumBatchRuntime) <= 0) {
            throw new IllegalArgumentException("leaseDuration must exceed the maximum check batch runtime");
        }
        if (staleAfter.compareTo(checkInterval) <= 0) {
            throw new IllegalArgumentException("staleAfter must exceed checkInterval");
        }
        if (retention.compareTo(staleAfter) <= 0) {
            throw new IllegalArgumentException("retention must exceed staleAfter");
        }
    }

    public record Http(
            Duration connectTimeout,
            Duration responseTimeout,
            Duration totalTimeout,
            long maxResponseBytes,
            int maxRedirects,
            int maxHeaderCount,
            int maxHeaderLineLength,
            int dnsThreads,
            int dnsQueueCapacity,
            int requestThreads,
            int requestQueueCapacity) {

        public Http {
            connectTimeout = requirePositive(connectTimeout, "http.connectTimeout");
            responseTimeout = requirePositive(responseTimeout, "http.responseTimeout");
            totalTimeout = requirePositive(totalTimeout, "http.totalTimeout");
            if (connectTimeout.compareTo(totalTimeout) > 0 || responseTimeout.compareTo(totalTimeout) > 0) {
                throw new IllegalArgumentException("HTTP phase timeout cannot exceed totalTimeout");
            }
            if (maxResponseBytes <= 0 || maxRedirects < 0 || maxRedirects > 3) {
                throw new IllegalArgumentException("HTTP response and redirect bounds are invalid");
            }
            if (maxHeaderCount <= 0 || maxHeaderLineLength <= 0) {
                throw new IllegalArgumentException("HTTP header bounds must be positive");
            }
            if (dnsThreads <= 0 || dnsQueueCapacity <= 0 || requestThreads <= 0 || requestQueueCapacity <= 0) {
                throw new IllegalArgumentException("HTTP executor bounds must be positive");
            }
        }
    }

    private static String requireToken(String token) {
        Objects.requireNonNull(token, "apiToken");
        if (token.isBlank() || token.length() < 32) {
            throw new IllegalArgumentException("apiToken must contain at least 32 characters");
        }
        return token;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}

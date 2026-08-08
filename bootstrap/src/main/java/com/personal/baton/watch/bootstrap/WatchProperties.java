package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
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

    static final int MAX_CHECK_BATCH_SIZE = 100;
    static final int MAX_MAINTENANCE_BATCH_SIZE = 1_000;

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
        if (checkBatchSize <= 0 || checkBatchSize > MAX_CHECK_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "checkBatchSize must be between 1 and " + MAX_CHECK_BATCH_SIZE);
        }
        if (maintenanceBatchSize <= 0 || maintenanceBatchSize > MAX_MAINTENANCE_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "maintenanceBatchSize must be between 1 and " + MAX_MAINTENANCE_BATCH_SIZE);
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
            OutboundResourceBounds.requireResponseBytes(
                    maxResponseBytes, OutboundResourceBounds.MAX_CHECK_RESPONSE_BYTES);
            if (maxRedirects < 0 || maxRedirects > 3) {
                throw new IllegalArgumentException("maxRedirects must be between zero and three");
            }
            OutboundResourceBounds.requireHeaderBounds(maxHeaderCount, maxHeaderLineLength);
            OutboundResourceBounds.requireDnsExecutorBounds(dnsThreads, dnsQueueCapacity);
            OutboundResourceBounds.requireRequestExecutorBounds(
                    requestThreads, requestQueueCapacity);
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

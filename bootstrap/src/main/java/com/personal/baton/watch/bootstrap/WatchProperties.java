package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
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
        @Min(1) @Max(MAX_CHECK_BATCH_SIZE) int checkBatchSize,
        @Min(1) @Max(MAX_MAINTENANCE_BATCH_SIZE) int maintenanceBatchSize,
        @Valid @NotNull Http http) {

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
        if (http != null && checkBatchSize >= 1 && checkBatchSize <= MAX_CHECK_BATCH_SIZE) {
            Duration maximumBatchRuntime;
            try {
                maximumBatchRuntime = http.totalTimeout().multipliedBy(checkBatchSize);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("check batch runtime is too large");
            }
            if (leaseDuration.compareTo(maximumBatchRuntime) <= 0) {
                throw new IllegalArgumentException("leaseDuration must exceed the maximum check batch runtime");
            }
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
            @Min(1) @Max(OutboundResourceBounds.MAX_CHECK_RESPONSE_BYTES) long maxResponseBytes,
            @Min(0) @Max(3) int maxRedirects,
            @Min(1) @Max(OutboundResourceBounds.MAX_HEADER_COUNT) int maxHeaderCount,
            @Min(1) @Max(OutboundResourceBounds.MAX_HEADER_LINE_LENGTH) int maxHeaderLineLength,
            @Min(1) @Max(OutboundResourceBounds.MAX_DNS_THREADS) int dnsThreads,
            @Min(1) @Max(OutboundResourceBounds.MAX_DNS_QUEUE_CAPACITY) int dnsQueueCapacity,
            @Min(1) @Max(OutboundResourceBounds.MAX_REQUEST_THREADS) int requestThreads,
            @Min(1) @Max(OutboundResourceBounds.MAX_REQUEST_QUEUE_CAPACITY) int requestQueueCapacity) {

        public Http {
            connectTimeout = requirePositive(connectTimeout, "http.connectTimeout");
            responseTimeout = requirePositive(responseTimeout, "http.responseTimeout");
            totalTimeout = requirePositive(totalTimeout, "http.totalTimeout");
            if (connectTimeout.compareTo(totalTimeout) > 0 || responseTimeout.compareTo(totalTimeout) > 0) {
                throw new IllegalArgumentException("HTTP phase timeout cannot exceed totalTimeout");
            }
        }
    }

    private static String requireToken(String token) {
        // Bean Validation failure analysis includes rejected values, so credentials stay procedural.
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

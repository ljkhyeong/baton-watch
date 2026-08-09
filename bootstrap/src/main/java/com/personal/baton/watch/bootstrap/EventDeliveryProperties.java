package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import com.personal.baton.watch.application.monitoring.service.EventDeliveryRetryPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("watch.event-delivery")
public record EventDeliveryProperties(
        boolean enabled,
        URI endpoint,
        String bearerToken,
        Duration pollInterval,
        Duration maintenanceInterval,
        Duration leaseDuration,
        Duration initialRetryDelay,
        Duration maxRetryDelay,
        Duration retention,
        @Min(1) @Max(MAX_DELIVERY_BATCH_SIZE) int batchSize,
        @Min(1) @Max(MAX_MAINTENANCE_BATCH_SIZE) int maintenanceBatchSize,
        @Valid @NotNull Http http) {

    static final int MAX_DELIVERY_BATCH_SIZE = 100;
    static final int MAX_MAINTENANCE_BATCH_SIZE = 1_000;

    public EventDeliveryProperties {
        pollInterval = requirePositive(pollInterval, "pollInterval");
        maintenanceInterval = requirePositive(maintenanceInterval, "maintenanceInterval");
        leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        EventDeliveryRetryPolicy retryPolicy = new EventDeliveryRetryPolicy(
                initialRetryDelay, maxRetryDelay);
        initialRetryDelay = retryPolicy.initialDelay();
        maxRetryDelay = retryPolicy.maxDelay();
        retention = requirePositive(retention, "retention");
        if (http != null && batchSize >= 1 && batchSize <= MAX_DELIVERY_BATCH_SIZE) {
            Duration maximumBatchRuntime;
            try {
                maximumBatchRuntime = http.totalTimeout().multipliedBy(batchSize);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("event delivery batch runtime is too large");
            }
            if (leaseDuration.compareTo(maximumBatchRuntime) <= 0) {
                throw new IllegalArgumentException("event delivery leaseDuration must exceed the maximum batch runtime");
            }
        }
        if (enabled) {
            // Boot may include rejected field values in validation reports; keep URL and token redacted.
            endpoint = requireEndpoint(endpoint);
            bearerToken = requireToken(bearerToken);
        }
    }

    public record Http(
            Duration connectTimeout,
            Duration responseTimeout,
            Duration totalTimeout,
            @Min(1) @Max(OutboundResourceBounds.MAX_EVENT_DELIVERY_RESPONSE_BYTES) long maxResponseBytes,
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
                throw new IllegalArgumentException("event delivery HTTP phase timeout cannot exceed totalTimeout");
            }
        }
    }

    private static URI requireEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isAbsolute()
                || endpoint.isOpaque()
                || !"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawFragment() != null
                || endpoint.getRawQuery() != null
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)) {
            throw new IllegalArgumentException("event delivery endpoint must be an absolute default-port HTTPS URL");
        }
        return endpoint;
    }

    private static String requireToken(String token) {
        Objects.requireNonNull(token, "bearerToken");
        if (token.isBlank() || token.length() < 32 || token.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("event delivery bearerToken must contain at least 32 safe characters");
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

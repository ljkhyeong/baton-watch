package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
        int batchSize,
        int maintenanceBatchSize,
        Http http) {

    static final int MAX_DELIVERY_BATCH_SIZE = 100;
    static final int MAX_MAINTENANCE_BATCH_SIZE = 1_000;

    public EventDeliveryProperties {
        pollInterval = requirePositive(pollInterval, "pollInterval");
        maintenanceInterval = requirePositive(maintenanceInterval, "maintenanceInterval");
        leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        initialRetryDelay = requirePositive(initialRetryDelay, "initialRetryDelay");
        maxRetryDelay = requirePositive(maxRetryDelay, "maxRetryDelay");
        retention = requirePositive(retention, "retention");
        if (initialRetryDelay.compareTo(maxRetryDelay) > 0) {
            throw new IllegalArgumentException("initialRetryDelay must not exceed maxRetryDelay");
        }
        if (batchSize <= 0 || batchSize > MAX_DELIVERY_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "batchSize must be between 1 and " + MAX_DELIVERY_BATCH_SIZE);
        }
        if (maintenanceBatchSize <= 0 || maintenanceBatchSize > MAX_MAINTENANCE_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "maintenanceBatchSize must be between 1 and " + MAX_MAINTENANCE_BATCH_SIZE);
        }
        Objects.requireNonNull(http, "http");
        Duration maximumBatchRuntime;
        try {
            maximumBatchRuntime = http.totalTimeout().multipliedBy(batchSize);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("event delivery batch runtime is too large");
        }
        if (leaseDuration.compareTo(maximumBatchRuntime) <= 0) {
            throw new IllegalArgumentException("event delivery leaseDuration must exceed the maximum batch runtime");
        }
        if (enabled) {
            endpoint = requireEndpoint(endpoint);
            bearerToken = requireToken(bearerToken);
        }
    }

    public record Http(
            Duration connectTimeout,
            Duration responseTimeout,
            Duration totalTimeout,
            long maxResponseBytes,
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
                throw new IllegalArgumentException("event delivery HTTP phase timeout cannot exceed totalTimeout");
            }
            OutboundResourceBounds.requireResponseBytes(
                    maxResponseBytes,
                    OutboundResourceBounds.MAX_EVENT_DELIVERY_RESPONSE_BYTES);
            OutboundResourceBounds.requireHeaderBounds(maxHeaderCount, maxHeaderLineLength);
            OutboundResourceBounds.requireDnsExecutorBounds(dnsThreads, dnsQueueCapacity);
            OutboundResourceBounds.requireRequestExecutorBounds(
                    requestThreads, requestQueueCapacity);
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

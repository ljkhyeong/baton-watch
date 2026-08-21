package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import com.personal.baton.watch.application.monitoring.service.TimeBoundaryPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("watch.event-delivery")
public record EventDeliveryProperties(
        boolean enabled,
        URI endpoint,
        String bearerToken,
        @NotNull @DurationMin(inclusive = false) Duration pollInterval,
        @NotNull @DurationMin(inclusive = false) Duration maintenanceInterval,
        @NotNull @DurationMin(inclusive = false)
        @DurationMax(days = TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET_DAYS)
        Duration leaseDuration,
        Duration initialRetryDelay,
        Duration maxRetryDelay,
        @NotNull @DurationMin(inclusive = false)
        @DurationMax(days = TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET_DAYS)
        Duration retention,
        @Min(1) @Max(MAX_DELIVERY_BATCH_SIZE) int batchSize,
        @Min(1) @Max(MAX_MAINTENANCE_BATCH_SIZE) int maintenanceBatchSize,
        @Valid @NotNull Http http) {

    static final int MAX_DELIVERY_BATCH_SIZE = 100;
    static final int MAX_MAINTENANCE_BATCH_SIZE = 1_000;

    public EventDeliveryProperties {
        if (http != null
                && leaseDuration != null
                && leaseDuration.isPositive()
                && http.totalTimeout() != null
                && http.totalTimeout().isPositive()
                && batchSize >= 1
                && batchSize <= MAX_DELIVERY_BATCH_SIZE) {
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
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(bearerToken, "bearerToken");
        }
    }

    public record Http(
            @NotNull @DurationMin(inclusive = false) Duration connectTimeout,
            @NotNull @DurationMin(inclusive = false) Duration responseTimeout,
            @NotNull @DurationMin(inclusive = false) Duration totalTimeout,
            @Min(1) @Max(OutboundResourceBounds.MAX_EVENT_DELIVERY_RESPONSE_BYTES) long maxResponseBytes,
            @Min(1) @Max(OutboundResourceBounds.MAX_HEADER_COUNT) int maxHeaderCount,
            @Min(1) @Max(OutboundResourceBounds.MAX_HEADER_LINE_LENGTH) int maxHeaderLineLength,
            @Min(1) @Max(OutboundResourceBounds.MAX_DNS_THREADS) int dnsThreads,
            @Min(1) @Max(OutboundResourceBounds.MAX_DNS_QUEUE_CAPACITY) int dnsQueueCapacity,
            @Min(1) @Max(OutboundResourceBounds.MAX_REQUEST_THREADS) int requestThreads,
            @Min(1) @Max(OutboundResourceBounds.MAX_REQUEST_QUEUE_CAPACITY) int requestQueueCapacity) {

        public Http {
            if (connectTimeout != null
                    && connectTimeout.isPositive()
                    && responseTimeout != null
                    && responseTimeout.isPositive()
                    && totalTimeout != null
                    && totalTimeout.isPositive()
                    && (connectTimeout.compareTo(totalTimeout) > 0
                            || responseTimeout.compareTo(totalTimeout) > 0)) {
                throw new IllegalArgumentException("event delivery HTTP phase timeout cannot exceed totalTimeout");
            }
        }
    }

}

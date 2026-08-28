package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import com.personal.baton.watch.application.monitoring.service.TimeBoundaryPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("watch")
public record WatchProperties(
        String apiToken,
        @NotNull @DurationMin(inclusive = false) Duration pollInterval,
        @NotNull @DurationMin(inclusive = false) Duration maintenanceInterval,
        @NotNull @DurationMin(inclusive = false) @DurationMax(seconds = 60) Duration workerExecutionBudget,
        @NotNull @DurationMin(inclusive = false)
        @DurationMax(days = TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET_DAYS)
        Duration leaseDuration,
        @NotNull @DurationMin(inclusive = false)
        @DurationMax(days = TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET_DAYS)
        Duration checkInterval,
        @NotNull @DurationMin(inclusive = false)
        @DurationMax(days = TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET_DAYS)
        Duration internalFailureRetryInterval,
        @NotNull @DurationMin(inclusive = false)
        @DurationMax(days = TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET_DAYS)
        Duration staleAfter,
        @NotNull @DurationMin(inclusive = false)
        @DurationMax(days = TimeBoundaryPolicy.MAX_SUPPORTED_OFFSET_DAYS)
        Duration retention,
        @Min(1) @Max(MAX_CHECK_BATCH_SIZE) int checkBatchSize,
        @Min(1) @Max(MAX_MAINTENANCE_BATCH_SIZE) int maintenanceBatchSize,
        @Valid @NotNull Http http) {

    static final int MAX_CHECK_BATCH_SIZE = 100;
    static final int MAX_MAINTENANCE_BATCH_SIZE = 1_000;
    static final int MAX_API_TOKEN_LENGTH = 200;
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("[A-Za-z0-9\\-._~+/]{32,}=*");

    public WatchProperties {
        requireToken(apiToken);
        if (staleAfter != null
                && staleAfter.isPositive()
                && checkInterval != null
                && checkInterval.isPositive()
                && staleAfter.compareTo(checkInterval) <= 0) {
            throw new IllegalArgumentException("staleAfter must exceed checkInterval");
        }
        if (retention != null
                && retention.isPositive()
                && staleAfter != null
                && staleAfter.isPositive()
                && retention.compareTo(staleAfter) <= 0) {
            throw new IllegalArgumentException("retention must exceed staleAfter");
        }
    }

    public record Http(
            @NotNull @DurationMin(inclusive = false) Duration connectTimeout,
            @NotNull @DurationMin(inclusive = false) Duration responseTimeout,
            @NotNull @DurationMin(inclusive = false) Duration totalTimeout,
            @Min(1) @Max(OutboundResourceBounds.MAX_CHECK_RESPONSE_BYTES) long maxResponseBytes,
            @Min(0) @Max(3) int maxRedirects,
            @Min(1) @Max(OutboundResourceBounds.MAX_HEADER_COUNT) int maxHeaderCount,
            @Min(1) @Max(OutboundResourceBounds.MAX_HEADER_LINE_LENGTH) int maxHeaderLineLength,
            @Min(1) @Max(OutboundResourceBounds.MAX_DNS_THREADS) int dnsThreads,
            @Min(1) @Max(OutboundResourceBounds.MAX_DNS_QUEUE_CAPACITY) int dnsQueueCapacity,
            @Min(1) @Max(OutboundResourceBounds.MAX_REQUEST_THREADS) int requestThreads,
            @Min(1) @Max(OutboundResourceBounds.MAX_REQUEST_QUEUE_CAPACITY) int requestQueueCapacity) {
    }

    private static void requireToken(String token) {
        // Bean Validation 실패 분석에는 거부된 값이 포함되므로 자격 증명은 명시적 코드로 검증한다.
        Objects.requireNonNull(token, "apiToken");
        if (token.length() > MAX_API_TOKEN_LENGTH || !BEARER_TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException(
                    "apiToken must contain at least 32 non-padding RFC 6750 token68 characters and at most 200 total characters");
        }
    }

}

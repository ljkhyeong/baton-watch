package com.personal.baton.watch.adapter.in.web.monitoring;

import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.port.in.GetMonitorProjectionsUseCase;
import com.personal.baton.watch.application.monitoring.port.in.SynchronizeMonitorUseCase;
import com.personal.baton.watch.application.monitoring.port.in.RequestMonitorCheckUseCase;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
@RequestMapping(path = "/api/v1/resource-monitors", produces = MediaType.APPLICATION_JSON_VALUE)
public final class ResourceMonitorController {

    private static final int MAX_BATCH_REFERENCES = 20;

    private final SynchronizeMonitorUseCase synchronizeMonitor;
    private final GetMonitorProjectionsUseCase getMonitorProjections;
    private final RequestMonitorCheckUseCase requestMonitorCheck;
    private final Clock clock;

    public ResourceMonitorController(
            SynchronizeMonitorUseCase synchronizeMonitor,
            GetMonitorProjectionsUseCase getMonitorProjections,
            RequestMonitorCheckUseCase requestMonitorCheck,
            Clock clock) {
        this.synchronizeMonitor = synchronizeMonitor;
        this.getMonitorProjections = getMonitorProjections;
        this.requestMonitorCheck = requestMonitorCheck;
        this.clock = clock;
    }

    @PutMapping(path = "/{resourceReference}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MonitorResponse synchronize(
            @PathVariable ResourceReference resourceReference,
            @Valid @RequestBody SynchronizeMonitorRequest request) {
        SynchronizationResult result = synchronizeMonitor.synchronize(request.toCommand(resourceReference));
        return switch (result.status()) {
            case APPLIED, UNCHANGED -> MonitorResponse.from(result.projection(), clock.instant());
            case STALE_REVISION -> throw MonitorApiException.staleRevision();
            case REVISION_CONFLICT -> throw MonitorApiException.revisionConflict();
        };
    }

    @GetMapping("/{resourceReference}")
    public MonitorResponse get(@PathVariable ResourceReference resourceReference) {
        return getMonitorProjections.get(List.of(resourceReference)).stream()
                .findFirst()
                .map(projection -> MonitorResponse.from(projection, clock.instant()))
                .orElseThrow(MonitorApiException::notFound);
    }

    @GetMapping
    public MonitorBatchResponse getBatch(WebRequest request) {
        List<ResourceReference> resourceReferences = batchReferences(request);
        return MonitorBatchResponse.from(
                resourceReferences, getMonitorProjections.get(resourceReferences), clock.instant());
    }

    @PostMapping("/{resourceReference}/check-requests")
    public ResponseEntity<MonitorCheckRequestResponse> requestCheck(
            @PathVariable ResourceReference resourceReference) {
        var result = requestMonitorCheck.requestCheck(resourceReference);
        return switch (result.status()) {
            case SCHEDULED, ALREADY_SCHEDULED, IN_PROGRESS -> ResponseEntity.accepted()
                    .body(new MonitorCheckRequestResponse(result.status().name(), result.nextCheckAt()));
            case NOT_FOUND -> throw MonitorApiException.notFound();
            case INACTIVE -> throw MonitorApiException.inactive();
            case RATE_LIMITED -> throw MonitorApiException.checkRequestRateLimited(result.retryAfterSeconds());
        };
    }

    /**
     * 쿼리 값을 그대로 검증한다. Spring의 컬렉션 변환은 값이 하나면 쉼표로 나누고 공백을 지우며,
     * Tomcat은 잘못된 쿼리 인코딩을 매개변수를 읽을 때 {@link IllegalStateException}으로 알린다.
     */
    private static List<ResourceReference> batchReferences(WebRequest request) {
        try {
            String[] values = request.getParameterValues("resourceReference");
            if (values == null || values.length > MAX_BATCH_REFERENCES) {
                throw MonitorApiException.invalidRequest();
            }
            return Arrays.stream(values).map(ResourceReference::new).toList();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw MonitorApiException.invalidRequest();
        }
    }
}

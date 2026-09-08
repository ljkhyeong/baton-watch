package com.personal.baton.watch.adapter.in.web.monitoring;

import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.port.in.GetMonitorProjectionUseCase;
import com.personal.baton.watch.application.monitoring.port.in.SynchronizeMonitorUseCase;
import com.personal.baton.watch.application.monitoring.port.in.RequestMonitorCheckUseCase;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/resource-monitors", produces = MediaType.APPLICATION_JSON_VALUE)
public final class ResourceMonitorController {

    private final SynchronizeMonitorUseCase synchronizeMonitor;
    private final GetMonitorProjectionUseCase getMonitorProjection;
    private final RequestMonitorCheckUseCase requestMonitorCheck;

    public ResourceMonitorController(
            SynchronizeMonitorUseCase synchronizeMonitor,
            GetMonitorProjectionUseCase getMonitorProjection,
            RequestMonitorCheckUseCase requestMonitorCheck) {
        this.synchronizeMonitor = synchronizeMonitor;
        this.getMonitorProjection = getMonitorProjection;
        this.requestMonitorCheck = requestMonitorCheck;
    }

    @PutMapping(path = "/{resourceReference}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MonitorResponse synchronize(
            @PathVariable ResourceReference resourceReference,
            @Valid @RequestBody SynchronizeMonitorRequest request) {
        SynchronizationResult result = synchronizeMonitor.synchronize(request.toCommand(resourceReference));
        return switch (result.status()) {
            case APPLIED, UNCHANGED -> MonitorResponse.from(result.projection());
            case STALE_REVISION -> throw MonitorApiException.staleRevision();
            case REVISION_CONFLICT -> throw MonitorApiException.revisionConflict();
        };
    }

    @GetMapping("/{resourceReference}")
    public MonitorResponse get(@PathVariable ResourceReference resourceReference) {
        return getMonitorProjection.get(resourceReference)
                .map(MonitorResponse::from)
                .orElseThrow(MonitorApiException::notFound);
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
}

package com.personal.baton.watch.adapter.in.web.system;

import com.personal.baton.watch.domain.system.SystemStatus;
import java.time.Instant;

public record SystemStatusResponse(
        String service, SystemStatus.State status, Instant observedAt) {

    public static SystemStatusResponse from(SystemStatus status) {
        return new SystemStatusResponse(
                status.service(),
                status.status(),
                status.observedAt()
        );
    }
}

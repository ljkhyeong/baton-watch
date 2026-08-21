package com.personal.baton.watch.application.system.service;

import com.personal.baton.watch.application.system.port.in.GetSystemStatusUseCase;
import com.personal.baton.watch.domain.system.SystemStatus;
import java.time.Clock;
import java.util.Objects;

public final class GetSystemStatusService implements GetSystemStatusUseCase {

    private static final String SERVICE_NAME = "baton-watch";

    private final Clock clock;

    public GetSystemStatusService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SystemStatus getStatus() {
        return new SystemStatus(SERVICE_NAME, SystemStatus.State.UP, clock.instant());
    }
}

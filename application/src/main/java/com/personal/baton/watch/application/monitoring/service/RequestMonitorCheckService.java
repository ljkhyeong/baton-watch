package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.model.MonitorCheckRequestResult;
import com.personal.baton.watch.application.monitoring.port.in.RequestMonitorCheckUseCase;
import com.personal.baton.watch.application.monitoring.port.out.MonitorPersistencePort;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import java.time.Clock;
import java.time.Duration;

/** 수동 요청의 최소 간격은 인스턴스 간 공유되는 모니터 행에서 적용한다. */
public final class RequestMonitorCheckService implements RequestMonitorCheckUseCase {

    private static final Duration MINIMUM_INTERVAL = Duration.ofSeconds(30);
    private final MonitorPersistencePort persistence;
    private final Clock clock;

    public RequestMonitorCheckService(MonitorPersistencePort persistence, Clock clock) {
        this.persistence = persistence;
        this.clock = clock;
    }

    @Override
    public MonitorCheckRequestResult requestCheck(ResourceReference resourceReference) {
        return persistence.requestCheck(resourceReference, clock.instant(), MINIMUM_INTERVAL);
    }
}

package com.personal.baton.watch.application.system.port.in;

import com.personal.baton.watch.domain.system.SystemStatus;

@FunctionalInterface
public interface GetSystemStatusUseCase {

    SystemStatus getStatus();
}


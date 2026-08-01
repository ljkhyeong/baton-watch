package com.personal.baton.watch.application.monitoring.port.in;

import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;

@FunctionalInterface
public interface SynchronizeMonitorUseCase {

    SynchronizationResult synchronize(SynchronizeMonitorCommand command);
}

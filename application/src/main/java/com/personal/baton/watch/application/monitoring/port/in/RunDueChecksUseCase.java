package com.personal.baton.watch.application.monitoring.port.in;

import com.personal.baton.watch.application.monitoring.model.DueCheckBatchResult;

@FunctionalInterface
public interface RunDueChecksUseCase {

    DueCheckBatchResult runDueChecks();
}

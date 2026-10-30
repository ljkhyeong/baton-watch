package com.personal.baton.watch.application.monitoring.port.in;

import java.time.Duration;

public interface MonitoringMaintenanceUseCase {

    int markStaleProjectionsUnknown();

    int purgeAttemptHistory();

    Duration checkScheduleDelay();

    Duration databaseClockOffset();
}

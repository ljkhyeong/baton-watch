package com.personal.baton.watch.application.monitoring.port.in;

import java.time.Duration;

@FunctionalInterface
public interface GetDatabaseClockOffsetUseCase {

    Duration getDatabaseClockOffset();
}

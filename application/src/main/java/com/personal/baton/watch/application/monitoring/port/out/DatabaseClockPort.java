package com.personal.baton.watch.application.monitoring.port.out;

import java.time.Instant;

@FunctionalInterface
public interface DatabaseClockPort {

    Instant currentTime();
}

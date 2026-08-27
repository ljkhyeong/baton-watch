package com.personal.baton.watch.application.monitoring.model;

import java.time.Duration;

public record DueCheckBatchResult(
        int claimed,
        int applied,
        int alreadyFinalized,
        int staleClaims,
        Duration maximumScheduleDelay) {}

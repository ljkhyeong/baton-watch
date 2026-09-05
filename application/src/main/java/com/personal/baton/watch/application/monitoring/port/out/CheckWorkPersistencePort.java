package com.personal.baton.watch.application.monitoring.port.out;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface CheckWorkPersistencePort {

    Optional<ClaimedCheck> claimDueCheck(Duration leaseDuration);

    CheckFinalizationStatus finalizeCheck(CheckFinalization finalization);

    Duration getOldestDueCheckDelay();

    int purgeAttempts(Instant completedBefore, int limit);
}

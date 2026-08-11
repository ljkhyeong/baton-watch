package com.personal.baton.watch.application.monitoring.port.out;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import java.time.Instant;
import java.util.List;

public interface CheckWorkPersistencePort {

    List<ClaimedCheck> claimDueChecks(Instant claimedAt, Instant leaseUntil, int limit);

    CheckFinalizationStatus finalizeCheck(CheckFinalization finalization);

    int purgeAttempts(Instant completedBefore, int limit);
}

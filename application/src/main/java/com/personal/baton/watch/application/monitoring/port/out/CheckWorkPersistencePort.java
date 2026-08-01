package com.personal.baton.watch.application.monitoring.port.out;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationResult;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import java.time.Instant;
import java.util.List;

public interface CheckWorkPersistencePort {

    List<ClaimedCheck> claimDueChecks(Instant claimedAt, Instant leaseUntil, int limit);

    CheckFinalizationResult finalizeCheck(CheckFinalization finalization);

    int purgeAttempts(Instant completedBefore, int limit);
}

package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.port.out.CheckWorkPersistencePort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

final class RecordingCheckWorkPersistence implements CheckWorkPersistencePort {

    final List<String> calls = new ArrayList<>();
    private final Queue<ClaimedCheck> claims;
    CheckFinalizationStatus finalizationStatus = CheckFinalizationStatus.APPLIED;
    int purgedAttempts;
    Duration leaseDuration;
    CheckFinalization finalization;
    Instant completedBefore;
    int limit;

    RecordingCheckWorkPersistence(ClaimedCheck... claims) {
        this.claims = new ArrayDeque<>(List.of(claims));
    }

    @Override
    public Optional<ClaimedCheck> claimDueCheck(Duration leaseDuration) {
        calls.add("claim");
        this.leaseDuration = leaseDuration;
        return Optional.ofNullable(claims.poll());
    }

    @Override
    public CheckFinalizationStatus finalizeCheck(CheckFinalization finalization) {
        calls.add("finalize");
        this.finalization = finalization;
        return finalizationStatus;
    }

    @Override
    public Duration getOldestDueCheckDelay() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int purgeAttempts(Instant completedBefore, int limit) {
        this.completedBefore = completedBefore;
        this.limit = limit;
        return purgedAttempts;
    }
}

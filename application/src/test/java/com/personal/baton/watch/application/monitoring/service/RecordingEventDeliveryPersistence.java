package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklogSnapshot;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

final class RecordingEventDeliveryPersistence implements HealthChangeEventDeliveryPersistencePort {

    final List<String> calls = new ArrayList<>();
    private final Queue<ClaimedHealthChangeEvent> claims;
    EventDeliveryFinalizationStatus finalizationStatus = EventDeliveryFinalizationStatus.APPLIED;
    int purgedEvents;
    EventDeliveryBacklogSnapshot backlogSnapshot;
    Duration leaseDuration;
    EventDeliveryFinalization finalization;
    Instant deliveredBefore;
    int limit;

    RecordingEventDeliveryPersistence(ClaimedHealthChangeEvent... claims) {
        this.claims = new ArrayDeque<>(List.of(claims));
    }

    @Override
    public Optional<ClaimedHealthChangeEvent> claimPendingEvent(Duration leaseDuration) {
        calls.add("claim");
        this.leaseDuration = leaseDuration;
        return Optional.ofNullable(claims.poll());
    }

    @Override
    public EventDeliveryFinalizationStatus finalizeDelivery(EventDeliveryFinalization finalization) {
        calls.add("finalize");
        this.finalization = finalization;
        return finalizationStatus;
    }

    @Override
    public int purgeDeliveredEvents(Instant deliveredBefore, int limit) {
        this.deliveredBefore = deliveredBefore;
        this.limit = limit;
        return purgedEvents;
    }

    @Override
    public EventDeliveryBacklogSnapshot getBacklogSnapshot() {
        return backlogSnapshot;
    }
}

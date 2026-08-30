package com.personal.baton.watch.application.monitoring.port.out;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklogSnapshot;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface HealthChangeEventDeliveryPersistencePort {

    Optional<ClaimedHealthChangeEvent> claimPendingEvent(Duration leaseDuration);

    EventDeliveryFinalizationStatus finalizeDelivery(EventDeliveryFinalization finalization);

    int purgeDeliveredEvents(Instant deliveredBefore, int limit);

    EventDeliveryBacklogSnapshot getBacklogSnapshot();
}

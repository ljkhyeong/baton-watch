package com.personal.baton.watch.application.monitoring.port.out;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklogSnapshot;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationResult;
import java.time.Instant;
import java.util.List;

public interface HealthChangeEventDeliveryPersistencePort {

    List<ClaimedHealthChangeEvent> claimPendingEvents(Instant claimedAt, Instant leaseUntil, int limit);

    EventDeliveryFinalizationResult finalizeDelivery(EventDeliveryFinalization finalization);

    int purgeDeliveredEvents(Instant deliveredBefore, int limit);

    EventDeliveryBacklogSnapshot getBacklogSnapshot();
}

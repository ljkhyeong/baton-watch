package com.personal.baton.watch.application.monitoring.port.out;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;

public interface HealthChangeEventSender {

    EventDeliveryObservation send(ClaimedHealthChangeEvent event);
}

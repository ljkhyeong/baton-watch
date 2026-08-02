package com.personal.baton.watch.application.monitoring.port.out;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;

public interface HealthChangeEventSender {

    EventDeliveryObservation send(HealthChangeEventPayload payload);
}

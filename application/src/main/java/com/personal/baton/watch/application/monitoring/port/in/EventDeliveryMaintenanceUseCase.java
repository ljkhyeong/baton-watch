package com.personal.baton.watch.application.monitoring.port.in;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryBacklog;

public interface EventDeliveryMaintenanceUseCase {

    int purgeDeliveredEvents();

    EventDeliveryBacklog eventDeliveryBacklog();
}

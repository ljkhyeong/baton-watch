package com.personal.baton.watch.application.monitoring.port.in;

import com.personal.baton.watch.application.monitoring.model.EventDeliveryBatchResult;

public interface RunEventDeliveriesUseCase {

    EventDeliveryBatchResult runEventDeliveries();
}

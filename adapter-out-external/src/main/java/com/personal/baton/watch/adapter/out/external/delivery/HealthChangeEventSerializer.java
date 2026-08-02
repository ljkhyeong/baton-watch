package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;

@FunctionalInterface
interface HealthChangeEventSerializer {

    byte[] serialize(HealthChangeEventPayload payload);
}

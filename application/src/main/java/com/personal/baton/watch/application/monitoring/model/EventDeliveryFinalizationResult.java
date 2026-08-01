package com.personal.baton.watch.application.monitoring.model;

import java.util.Objects;

public record EventDeliveryFinalizationResult(EventDeliveryFinalizationStatus status) {

    public EventDeliveryFinalizationResult {
        Objects.requireNonNull(status, "status");
    }
}

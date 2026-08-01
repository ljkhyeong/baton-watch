package com.personal.baton.watch.application.monitoring.model;

import java.util.Objects;

public record CheckFinalizationResult(CheckFinalizationStatus status) {

    public CheckFinalizationResult {
        Objects.requireNonNull(status, "status");
    }
}

package com.personal.baton.watch.domain.system;

import java.time.Instant;
import java.util.Objects;

public record SystemStatus(String service, State status, Instant observedAt) {

    public SystemStatus {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    public enum State {
        UP
    }
}


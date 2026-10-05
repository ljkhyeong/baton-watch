package com.personal.baton.watch.application.monitoring.model;

import java.time.Duration;
import java.util.Optional;

public record EventDeliveryBacklog(long pendingCount, Optional<Duration> oldestEventAge) {
}

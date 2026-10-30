package com.personal.baton.watch.application.monitoring.model;

import java.time.Duration;

/** 미전달 이벤트가 없으면 {@code oldestEventAge}는 0이다. */
public record EventDeliveryBacklog(long pendingCount, Duration oldestEventAge) {
}

package com.personal.baton.watch.application.monitoring.model;

public record EventDeliveryBatchResult(
        int claimed,
        int delivered,
        int retryScheduled,
        int alreadyDelivered,
        int staleClaims) {}

package com.personal.baton.watch.application.monitoring.model;

public record EventDeliveryBatchResult(
        int claimed,
        int delivered,
        int retryScheduled,
        int alreadyDelivered,
        int staleClaims) {

    public EventDeliveryBatchResult {
        if (claimed < 0 || delivered < 0 || retryScheduled < 0 || alreadyDelivered < 0 || staleClaims < 0) {
            throw new IllegalArgumentException("batch result counts must be non-negative");
        }
        if (delivered + retryScheduled + alreadyDelivered + staleClaims != claimed) {
            throw new IllegalArgumentException("finalization counts must equal claimed events");
        }
    }
}

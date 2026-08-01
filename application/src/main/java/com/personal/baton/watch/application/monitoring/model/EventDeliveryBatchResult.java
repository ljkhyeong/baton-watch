package com.personal.baton.watch.application.monitoring.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record EventDeliveryBatchResult(
        int claimed,
        int delivered,
        int retryScheduled,
        int alreadyDelivered,
        int staleClaims,
        Map<EventDeliveryOutcome, Integer> outcomes) {

    public EventDeliveryBatchResult {
        if (claimed < 0 || delivered < 0 || retryScheduled < 0 || alreadyDelivered < 0 || staleClaims < 0) {
            throw new IllegalArgumentException("batch result counts must be non-negative");
        }
        if (delivered + retryScheduled + alreadyDelivered + staleClaims != claimed) {
            throw new IllegalArgumentException("finalization counts must equal claimed events");
        }
        Objects.requireNonNull(outcomes, "outcomes");
        EnumMap<EventDeliveryOutcome, Integer> copy = new EnumMap<>(EventDeliveryOutcome.class);
        outcomes.forEach((outcome, count) -> {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(count, "outcome count");
            if (count <= 0) {
                throw new IllegalArgumentException("outcome counts must be positive");
            }
            copy.put(outcome, count);
        });
        int observed = copy.values().stream().mapToInt(Integer::intValue).sum();
        if (observed != claimed) {
            throw new IllegalArgumentException("outcome counts must equal claimed events");
        }
        outcomes = Map.copyOf(copy);
    }
}

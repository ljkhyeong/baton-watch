package com.personal.baton.watch.application.monitoring.model;

public record DueCheckBatchResult(int claimed, int applied, int alreadyFinalized, int staleClaims) {

    public DueCheckBatchResult {
        if (claimed < 0 || applied < 0 || alreadyFinalized < 0 || staleClaims < 0) {
            throw new IllegalArgumentException("batch result counts must be non-negative");
        }
        if (applied + alreadyFinalized + staleClaims != claimed) {
            throw new IllegalArgumentException("finalization counts must equal claimed checks");
        }
    }
}

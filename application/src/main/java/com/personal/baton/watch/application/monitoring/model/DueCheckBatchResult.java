package com.personal.baton.watch.application.monitoring.model;

public record DueCheckBatchResult(int claimed, int applied, int alreadyFinalized, int staleClaims) {}

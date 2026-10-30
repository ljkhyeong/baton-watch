package com.personal.baton.watch.application.monitoring.model;

/** {@code egressSuspected}는 WATCH 쪽 외부 연결 장애로 보고 내부 실패로 기록한 점검 수다. */
public record DueCheckBatchResult(
        int claimed,
        int applied,
        int alreadyFinalized,
        int staleClaims,
        int egressSuspected) {}

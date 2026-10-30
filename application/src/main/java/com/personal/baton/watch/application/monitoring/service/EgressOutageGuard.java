package com.personal.baton.watch.application.monitoring.service;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 마지막 HTTP 응답 뒤 서로 다른 호스트 다섯 곳이 모두 응답 없이 실패하면 WATCH 쪽 외부 연결 장애로 본다.
 * 어느 대상이든 HTTP 응답을 받으면 해제한다. 판단은 프로세스 메모리 안에서만 하며 호스트 이름은 개수를 세는 데만 쓴다.
 */
final class EgressOutageGuard {

    static final int SUSPECTED_HOST_COUNT = 5;

    private final Set<String> silentHosts = new HashSet<>();

    /** 점검 결과를 반영하고, 이 실패를 대상이 아닌 WATCH 쪽 장애로 봐야 하면 {@code true}를 반환한다. */
    synchronized boolean suspectsOutage(TargetUrl targetUrl, CheckObservation observation) {
        CheckOutcome outcome = observation.outcome();
        // 리다이렉트 응답을 받은 뒤의 실패도 외부 연결이 동작했다는 근거다.
        if (observation.responseReceived() || observation.redirectCount() > 0) {
            silentHosts.clear();
            return false;
        }
        if (!outcome.isConclusive()) {
            return false;
        }
        if (silentHosts.size() < SUSPECTED_HOST_COUNT) {
            silentHosts.add(targetUrl.uri().getHost().toLowerCase(Locale.ROOT));
        }
        return silentHosts.size() >= SUSPECTED_HOST_COUNT;
    }
}

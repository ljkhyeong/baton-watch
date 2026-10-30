package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.net.URI;

/** 설정된 단일 BATON 콜백 엔드포인트의 정적 정책. */
final class DeliveryEndpointPolicy {

    private static final String REJECTION_MESSAGE =
            "event delivery endpoint violates policy";

    /** 정책을 만족하면 설정한 URI를 그대로 반환해 퍼센트 인코딩과 원래 호스트 표기를 유지한다. */
    URI validate(URI endpoint) {
        final TargetUrl target;
        try {
            target = new TargetUrl(endpoint.toString());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(REJECTION_MESSAGE);
        }
        URI validated = target.uri();
        if (!validated.getScheme().equalsIgnoreCase("https") || validated.getRawQuery() != null) {
            throw new IllegalArgumentException(REJECTION_MESSAGE);
        }
        return endpoint;
    }
}

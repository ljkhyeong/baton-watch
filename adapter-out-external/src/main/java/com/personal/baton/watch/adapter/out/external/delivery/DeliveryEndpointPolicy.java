package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.net.URI;
import java.util.Locale;

/** 설정된 단일 BATON 콜백 엔드포인트의 정적 정책. */
final class DeliveryEndpointPolicy {

    private static final String REJECTION_MESSAGE =
            "event delivery endpoint violates policy";

    ValidatedDeliveryEndpoint validate(URI endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException(REJECTION_MESSAGE);
        }
        final TargetUrl target;
        try {
            target = new TargetUrl(endpoint.toString()).requireSafeEncodedCharacters();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(REJECTION_MESSAGE);
        }
        URI validated = target.uri();
        if (!target.protocol().equals("https") || validated.getRawQuery() != null) {
            throw new IllegalArgumentException(REJECTION_MESSAGE);
        }
        return new ValidatedDeliveryEndpoint(endpoint, validated.getHost().toLowerCase(Locale.ROOT));
    }
}

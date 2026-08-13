package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.net.URI;
import java.util.Locale;

/** 설정된 단일 BATON 콜백 엔드포인트의 정적 정책. */
final class DeliveryEndpointPolicy {

    ValidatedDeliveryEndpoint validate(URI endpoint) throws DeliveryPolicyException {
        if (endpoint == null) {
            throw new DeliveryPolicyException();
        }
        final TargetUrl target;
        try {
            target = new TargetUrl(endpoint.toString()).requireSafeEncodedCharacters();
        } catch (IllegalArgumentException exception) {
            throw new DeliveryPolicyException();
        }
        URI validated = target.uri();
        if (!target.protocol().equals("https") || validated.getRawQuery() != null) {
            throw new DeliveryPolicyException();
        }
        return new ValidatedDeliveryEndpoint(endpoint, validated.getHost().toLowerCase(Locale.ROOT));
    }
}

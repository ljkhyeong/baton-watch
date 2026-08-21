package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.net.URI;
import java.util.Locale;

/** 기준 {@code TargetUrl} 정책을 홉별 리다이렉트 및 순환 처리에 맞게 연결한다. */
final class TargetUriPolicy {

    ValidatedUri prepare(TargetUrl targetUrl) {
        targetUrl.requireSafeEncodedCharacters();
        URI uri = targetUrl.uri();
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String hostname = uri.getHost();
        return new ValidatedUri(uri, scheme, hostname, loopKey(uri, scheme, hostname));
    }

    ValidatedUri validate(URI uri) {
        return prepare(new TargetUrl(uri.toString()));
    }

    URI resolveRedirect(ValidatedUri current, String location) {
        TargetUrl.requireSafeReferenceCharacters(location);
        return current.uri().resolve(location);
    }

    private static String loopKey(URI uri, String scheme, String hostname) {
        URI normalized = uri.normalize();
        String path = normalized.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = normalized.getRawQuery();
        return scheme
                + "://"
                + hostname.toLowerCase(Locale.ROOT)
                + path
                + (query == null ? "" : "?" + query);
    }
}

package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.net.URI;
import java.util.Locale;
import org.apache.hc.client5.http.utils.URIUtils;

/** 기준 {@code TargetUrl} 정책을 홉별 리다이렉트 및 순환 처리에 맞게 연결한다. */
final class TargetUriPolicy {

    ValidatedUri prepare(TargetUrl targetUrl) {
        targetUrl.requireSafeEncodedCharacters();
        URI uri = targetUrl.uri();
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String hostname = uri.getHost();
        return new ValidatedUri(uri, scheme, hostname, loopKey(uri, scheme, hostname));
    }

    URI resolveRedirect(ValidatedUri current, String location) {
        TargetUrl.requireSafeReferenceCharacters(location);
        // 쿼리만 바뀔 때 JDK가 경로를 버리는 문제를 보완하고, 나머지 참조는 기존 해석을 유지한다.
        return location.startsWith("?")
                ? URIUtils.resolve(current.uri(), location)
                : current.uri().resolve(location);
    }

    private static String loopKey(URI uri, String scheme, String hostname) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        return scheme
                + "://"
                + hostname.toLowerCase(Locale.ROOT)
                + path
                + (query == null ? "" : "?" + query);
    }
}

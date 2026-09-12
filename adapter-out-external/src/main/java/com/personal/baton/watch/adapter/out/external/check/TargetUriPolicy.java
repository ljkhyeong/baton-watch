package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.net.URI;
import java.util.ArrayList;
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
        if (location.startsWith("?")) {
            return URIUtils.resolve(current.uri(), location);
        }
        URI reference = URI.create(location);
        URI resolved;
        if (reference.isAbsolute() || reference.getRawAuthority() != null
                || reference.getRawPath().isEmpty() || reference.getRawPath().startsWith("/")) {
            resolved = current.uri().resolve(reference);
        } else {
            // JDK의 상대 경로 정규화는 빈 구간도 제거하므로, 경로 병합에서는 슬래시를 보존한다.
            String basePath = current.uri().getRawPath();
            String directory = basePath.isEmpty() ? "/" : basePath.substring(0, basePath.lastIndexOf('/') + 1);
            String suffix = location.substring(reference.getRawPath().length());
            resolved = URI.create(current.uri().getScheme() + "://" + current.uri().getRawAuthority()
                    + directory + reference.getRawPath() + suffix);
        }

        String path = resolved.getRawPath();
        if (path == null || path.isEmpty() || resolved.getRawAuthority() == null) {
            return resolved;
        }
        String query = resolved.getRawQuery();
        String fragment = resolved.getRawFragment();
        return URI.create(resolved.getScheme() + "://" + resolved.getRawAuthority() + removeDotSegments(path)
                + (query == null ? "" : "?" + query)
                + (fragment == null ? "" : "#" + fragment));
    }

    /** 합친 절대 경로에서 RFC 3986의 점 구간만 제거하고 빈 구간과 인코딩은 유지한다. */
    private static String removeDotSegments(String path) {
        var segments = new ArrayList<String>();
        for (String segment : path.substring(1).split("/", -1)) {
            if (segment.equals("..")) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
            } else if (!segment.equals(".")) {
                segments.add(segment);
            }
        }
        if (path.endsWith("/.") || path.endsWith("/..")) {
            segments.add("");
        }
        return "/" + String.join("/", segments);
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

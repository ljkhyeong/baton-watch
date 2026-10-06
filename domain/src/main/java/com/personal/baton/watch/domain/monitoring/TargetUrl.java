package com.personal.baton.watch.domain.monitoring;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

public record TargetUrl(String value) {

    public static final int MAX_LENGTH = 2_048;

    private static final Pattern HOST_LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern NUMERIC_ADDRESS_COMPONENT = Pattern.compile("(?:0[xX][0-9A-Fa-f]+|[0-9]+)");
    /** 인코딩된 제어 문자(0x00-0x1F), DEL, 역슬래시다. */
    private static final Pattern UNSAFE_ESCAPE = Pattern.compile("%(?:[01]\\p{XDigit}|7[Ff]|5[Cc])");

    public TargetUrl {
        requireSafeReferenceCharacters(value);
        validateUri(parse(value));
    }

    public URI uri() {
        return parse(value);
    }

    /** 대상 URL과 정규화 전 리다이렉트 주소에서 허용하지 않는 원문·인코딩 문자를 검사한다. */
    public static void requireSafeReferenceCharacters(String value) {
        Objects.requireNonNull(value, "value");
        validateRawCharacters(value);
        if (UNSAFE_ESCAPE.matcher(value).find()) {
            throw invalid();
        }
    }

    @Override
    public String toString() {
        return "[target-url]";
    }

    private static void validateRawCharacters(String value) {
        if (value.isEmpty()
                || value.length() > MAX_LENGTH
                || value.codePoints().anyMatch(codePoint ->
                        codePoint == '\\'
                                || Character.isISOControl(codePoint)
                                || Character.getType(codePoint) == Character.SURROGATE)) {
            throw invalid();
        }
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException exception) {
            throw invalid();
        }
    }

    private static void validateUri(URI uri) {
        String scheme = uri.getScheme();
        boolean http = "http".equalsIgnoreCase(scheme);
        if (!http && !"https".equalsIgnoreCase(scheme)) {
            throw invalid();
        }
        if (uri.getRawFragment() != null) {
            throw invalid();
        }

        String host = uri.getHost();
        if (host == null || isIpLiteral(host) || !isUnambiguousHostname(host)) {
            throw invalid();
        }

        int port = uri.getPort();
        int defaultPort = http ? 80 : 443;
        if (port != -1 && port != defaultPort) {
            throw invalid();
        }
        String expectedAuthority = port == -1 ? host : host + ":" + port;
        if (!expectedAuthority.equalsIgnoreCase(uri.getRawAuthority())) {
            throw invalid();
        }
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return Arrays.stream(host.split("\\.", -1))
                .allMatch(NUMERIC_ADDRESS_COMPONENT.asMatchPredicate());
    }

    private static boolean isUnambiguousHostname(String host) {
        if (host.length() > 253) {
            return false;
        }
        return Arrays.stream(host.split("\\.", -1))
                .allMatch(HOST_LABEL.asMatchPredicate());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("target URL violates the static target policy");
    }
}

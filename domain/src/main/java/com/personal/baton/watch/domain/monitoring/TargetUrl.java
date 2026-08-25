package com.personal.baton.watch.domain.monitoring;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record TargetUrl(String value) {

    public static final int MAX_LENGTH = 2_048;

    private static final Pattern HOST_LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern NUMERIC_ADDRESS_COMPONENT = Pattern.compile("(?:0[xX][0-9A-Fa-f]+|[0-9]+)");

    public TargetUrl {
        Objects.requireNonNull(value, "value");
        validateRawCharacters(value);
        validateUri(parse(value));
    }

    public URI uri() {
        return parse(value);
    }

    /** 기존 행의 재구성을 막지 않으면서 인코딩된 문자 제한을 적용합니다. */
    public TargetUrl requireSafeEncodedCharacters() {
        validateEncodedCharacters(value);
        return this;
    }

    /** URI 해석 과정에서 정규화되기 전에 원시 대상 또는 리다이렉트 참조를 검증합니다. */
    public static void requireSafeReferenceCharacters(String value) {
        Objects.requireNonNull(value, "value");
        validateRawCharacters(value);
        validateEncodedCharacters(value);
    }

    @Override
    public String toString() {
        return "[target-url]";
    }

    private static void validateRawCharacters(String value) {
        if (value.isEmpty()
                || value.length() > MAX_LENGTH
                || value.codePoints().anyMatch(codePoint ->
                        codePoint == '\\' || Character.isISOControl(codePoint))) {
            throw invalid();
        }
    }

    private static void validateEncodedCharacters(String value) {
        for (int index = 0; index + 2 < value.length(); index++) {
            if (value.charAt(index) != '%') {
                continue;
            }
            int high = Character.digit(value.charAt(index + 1), 16);
            int low = Character.digit(value.charAt(index + 2), 16);
            if (high < 0 || low < 0) {
                continue;
            }
            int decoded = high * 16 + low;
            if (decoded <= 0x1f || decoded == 0x7f || decoded == '\\') {
                throw invalid();
            }
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
        if (!uri.isAbsolute()) {
            throw invalid();
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
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
        int defaultPort = scheme.equals("http") ? 80 : 443;
        if (port != -1 && port != defaultPort) {
            throw invalid();
        }
        String expectedAuthority = port == -1 ? host : host + ":" + port;
        if (!expectedAuthority.equalsIgnoreCase(uri.getRawAuthority())) {
            throw invalid();
        }
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0 || host.startsWith("[") || host.endsWith("]")) {
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

package com.personal.baton.watch.adapter.out.external.check;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Static URL policy. DNS and address checks are deliberately separate. */
final class TargetUriPolicy {

    private static final int MAX_URL_LENGTH = 2_048;
    private static final Pattern HOST_LABEL =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern NUMERIC_ADDRESS = Pattern.compile(
            "(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+)){0,3}");

    ValidatedUri validate(String rawValue) throws TargetPolicyException {
        if (rawValue == null || rawValue.isEmpty() || rawValue.length() > MAX_URL_LENGTH) {
            throw new TargetPolicyException();
        }
        rejectProhibitedCharacters(rawValue);
        try {
            return validate(new URI(rawValue));
        } catch (URISyntaxException exception) {
            throw new TargetPolicyException();
        }
    }

    ValidatedUri validate(URI uri) throws TargetPolicyException {
        if (uri == null) {
            throw new TargetPolicyException();
        }
        String rawValue = uri.toString();
        if (rawValue.isEmpty() || rawValue.length() > MAX_URL_LENGTH) {
            throw new TargetPolicyException();
        }
        rejectProhibitedCharacters(rawValue);
        if (!uri.isAbsolute() || uri.isOpaque() || uri.getScheme() == null) {
            throw new TargetPolicyException();
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new TargetPolicyException();
        }
        if (uri.getRawAuthority() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null) {
            throw new TargetPolicyException();
        }

        String hostname = uri.getHost();
        if (hostname == null || hostname.isBlank() || !isUnambiguousHostname(hostname)) {
            throw new TargetPolicyException();
        }
        if (isIpLiteralOrAlternateForm(hostname)) {
            throw new TargetPolicyException();
        }

        int defaultPort = scheme.equals("http") ? 80 : 443;
        int port = uri.getPort();
        if (port != -1 && port != defaultPort) {
            throw new TargetPolicyException();
        }

        String expectedAuthority = hostname + (port == -1 ? "" : ":" + port);
        if (!uri.getRawAuthority().equalsIgnoreCase(expectedAuthority)) {
            // Reject empty, signed, padded, encoded, or otherwise ambiguous port/host syntax.
            throw new TargetPolicyException();
        }

        return new ValidatedUri(uri, scheme, hostname, loopKey(uri, scheme, hostname));
    }

    URI resolveRedirect(ValidatedUri current, String location) throws TargetPolicyException {
        if (location == null || location.isEmpty()) {
            throw new TargetPolicyException();
        }
        rejectProhibitedCharacters(location);
        try {
            return current.uri().resolve(new URI(location));
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new TargetPolicyException();
        }
    }

    private static boolean isUnambiguousHostname(String hostname) {
        if (hostname.length() > 253 || hostname.startsWith(".") || hostname.endsWith(".")) {
            return false;
        }
        for (String label : hostname.split("\\.", -1)) {
            if (!HOST_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpLiteralOrAlternateForm(String hostname) {
        return hostname.indexOf(':') >= 0
                || hostname.startsWith("[")
                || hostname.endsWith("]")
                || NUMERIC_ADDRESS.matcher(hostname).matches();
    }

    private static void rejectProhibitedCharacters(String value) throws TargetPolicyException {
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (codePoint == '\\' || Character.getType(codePoint) == Character.CONTROL) {
                throw new TargetPolicyException();
            }
            index += Character.charCount(codePoint);
        }
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
                throw new TargetPolicyException();
            }
        }
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

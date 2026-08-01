package com.personal.baton.watch.adapter.out.external.delivery;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** Static policy for the single configured BATON callback endpoint. */
final class DeliveryEndpointPolicy {

    private static final int MAX_ENDPOINT_LENGTH = 2_048;
    private static final Pattern HOST_LABEL =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern NUMERIC_ADDRESS = Pattern.compile(
            "(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+)){0,3}");

    ValidatedDeliveryEndpoint validate(URI endpoint) throws DeliveryPolicyException {
        if (endpoint == null) {
            throw new DeliveryPolicyException();
        }
        String rawValue = endpoint.toString();
        if (rawValue.isEmpty() || rawValue.length() > MAX_ENDPOINT_LENGTH) {
            throw new DeliveryPolicyException();
        }
        rejectProhibitedCharacters(rawValue);
        if (!endpoint.isAbsolute()
                || endpoint.isOpaque()
                || endpoint.getScheme() == null
                || !endpoint.getScheme().equalsIgnoreCase("https")) {
            throw new DeliveryPolicyException();
        }
        if (endpoint.getRawAuthority() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawFragment() != null
                || endpoint.getRawQuery() != null) {
            throw new DeliveryPolicyException();
        }

        String hostname = endpoint.getHost();
        if (hostname == null || hostname.isBlank() || !isUnambiguousHostname(hostname)) {
            throw new DeliveryPolicyException();
        }
        if (isIpLiteralOrAlternateForm(hostname)) {
            throw new DeliveryPolicyException();
        }

        int port = endpoint.getPort();
        if (port != -1 && port != 443) {
            throw new DeliveryPolicyException();
        }
        String expectedAuthority = hostname + (port == -1 ? "" : ":" + port);
        if (!endpoint.getRawAuthority().equalsIgnoreCase(expectedAuthority)) {
            throw new DeliveryPolicyException();
        }
        return new ValidatedDeliveryEndpoint(endpoint, hostname.toLowerCase(Locale.ROOT));
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

    private static void rejectProhibitedCharacters(String value) throws DeliveryPolicyException {
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (codePoint == '\\' || Character.getType(codePoint) == Character.CONTROL) {
                throw new DeliveryPolicyException();
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
                throw new DeliveryPolicyException();
            }
        }
    }
}

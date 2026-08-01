package com.personal.baton.watch.adapter.out.external.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TargetUriPolicyTest {

    private final TargetUriPolicy policy = new TargetUriPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.com/path?token=not-logged",
        "HTTPS://Example.COM:443/path",
        "http://example.com:80/",
        "http://single-label/path"
    })
    void acceptsOnlyUnambiguousHostnameTargetsOnDefaultPorts(String value) throws Exception {
        ValidatedUri target = policy.validate(value);

        assertEquals(new URI(value), target.uri());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/relative",
        "ftp://example.com/file",
        "https://example.com:8443/",
        "https://user:secret@example.com/",
        "https://example.com/path#fragment",
        "https://127.0.0.1/",
        "https://[2001:4860:4860::8888]/",
        "https://2130706433/",
        "https://0x7f000001/",
        "https://127.1/",
        "https://0x7f.1/",
        "https://example.com:/",
        "https://example.com:0443/",
        "https://example.com./",
        "https://bad_host.example/",
        "https://example.com\\@evil.example/",
        "https://example.com/%0d%0aHost:evil.example",
        "https://example.com/%5c%5cevil.example",
        "https://example.com%2e.evil.example/",
        "https:///missing-host",
        "https://example.com:443:443/"
    })
    void rejectsMalformedAmbiguousAndIpLiteralTargets(String value) {
        assertThrows(TargetPolicyException.class, () -> policy.validate(value));
    }

    @Test
    void rejectsRawControlCharacters() {
        assertThrows(TargetPolicyException.class, () -> policy.validate("https://example.com/a\nb"));
    }

    @Test
    void resolvesRelativeRedirectWithoutChangingTheOriginalHostname() throws Exception {
        ValidatedUri current = policy.validate("https://Example.COM/a/one?old=true");

        URI redirect = policy.resolveRedirect(current, "../two?new=true");
        ValidatedUri validated = policy.validate(redirect);

        assertEquals("https://Example.COM/two?new=true", validated.uri().toString());
        assertEquals("Example.COM", validated.hostname());
    }

    @Test
    void canonicalLoopKeyNormalizesHostCaseDefaultPortAndEmptyPath() throws Exception {
        ValidatedUri first = policy.validate("https://EXAMPLE.com:443");
        ValidatedUri second = policy.validate("https://example.com/");

        assertEquals(first.loopKey(), second.loopKey());
    }
}

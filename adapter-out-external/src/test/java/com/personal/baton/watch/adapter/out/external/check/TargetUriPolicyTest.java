package com.personal.baton.watch.adapter.out.external.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.personal.baton.watch.domain.monitoring.TargetUrl;
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
        ValidatedUri target = policy.prepare(new TargetUrl(value));

        assertEquals(new URI(value), target.uri());
    }

    @Test
    void mapsAnInvalidRedirectTargetToAnAdapterPolicyFailure() {
        assertThrows(
                TargetPolicyException.class,
                () -> policy.validate(URI.create("https://example.com/%0d%0aHost:internal")));
    }

    @Test
    void resolvesRelativeRedirectWithoutChangingTheOriginalHostname() throws Exception {
        ValidatedUri current = policy.prepare(new TargetUrl("https://Example.COM/a/one?old=true"));

        URI redirect = policy.resolveRedirect(current, "../two?new=true");
        ValidatedUri validated = policy.validate(redirect);

        assertEquals("https://Example.COM/two?new=true", validated.uri().toString());
        assertEquals("Example.COM", validated.hostname());
    }

    @Test
    void canonicalLoopKeyNormalizesHostCaseDefaultPortAndEmptyPath() throws Exception {
        ValidatedUri first = policy.prepare(new TargetUrl("https://EXAMPLE.com:443"));
        ValidatedUri second = policy.prepare(new TargetUrl("https://example.com/"));

        assertEquals(first.loopKey(), second.loopKey());
    }
}

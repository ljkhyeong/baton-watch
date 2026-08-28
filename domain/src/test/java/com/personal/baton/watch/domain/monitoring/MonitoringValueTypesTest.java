package com.personal.baton.watch.domain.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MonitoringValueTypesTest {

    @Test
    void acceptsBoundedResourceReferencesAndNonNegativeRevisions() {
        assertEquals("role:resource-1_v2.test", new ResourceReference("role:resource-1_v2.test").value());
        assertEquals(0, new SourceRevision(0).value());
    }

    @Test
    void rejectsInvalidResourceReferencesAndNegativeRevisions() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceReference(""));
        assertThrows(IllegalArgumentException.class, () -> new ResourceReference("contains space"));
        assertThrows(IllegalArgumentException.class, () -> new ResourceReference("a".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> new SourceRevision(-1));
    }

    @Test
    void acceptsAbsoluteHttpTargetsWithDefaultPortsAndQueries() {
        new TargetUrl("http://example.com:80/health?detail=short");
        new TargetUrl("https://status.example.com:443");
        new TargetUrl("https://example.com/a%20path?literal=%25").requireSafeEncodedCharacters();
        String prefix = "https://example.com/";
        new TargetUrl(prefix + "a".repeat(TargetUrl.MAX_LENGTH - prefix.length()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TargetUrl(prefix + "a".repeat(TargetUrl.MAX_LENGTH - prefix.length() + 1)));
    }

    @Test
    void rejectsTargetsOutsideTheStaticPolicy() {
        String[] invalidTargets = {
            "/relative",
            "ftp://example.com/file",
            "https:opaque",
            "https://user@example.com/health",
            "https://example.com/health#fragment",
            "https://example.com:8443/health",
            "https://example.com:/health",
            "https://example.com:0443/health",
            "https://127.0.0.1/health",
            "https://[::1]/health",
            "https://[2001:4860:4860::8888]/health",
            "http://2130706433/health",
            "http://0x7f000001/health",
            "http://127.1/health",
            "http://0x7f.1/health",
            "https://1.2.3.4.5/health",
            "https://.example.com/health",
            "https://example.com./health",
            "https://bad_host.example/health",
            "https://example.com\\health",
            "https://example.com\\@evil.example/health",
            "https://example.com/health\nnext",
            "https://example.com%2e.evil.example/health",
            "https:///missing-host",
            "https://example.com:443:443/health"
        };

        for (String target : invalidTargets) {
            assertThrows(IllegalArgumentException.class, () -> new TargetUrl(target));
        }
    }

    @Test
    void rehydratesHistoricalTargetsButRejectsUnsafeEscapesBeforeOutboundUse() {
        String[] unsafeTargets = {
            "https://example.com/%00",
            "https://example.com/%1f",
            "https://example.com/%7F",
            "https://example.com/%0d%0aHost:internal",
            "https://example.com/%5C%5Cevil.example"
        };

        for (String value : unsafeTargets) {
            TargetUrl historicalTarget = new TargetUrl(value);
            assertThrows(IllegalArgumentException.class, historicalTarget::requireSafeEncodedCharacters);
        }
    }

    @Test
    void hidesSensitiveValuesFromStringRepresentations() {
        TargetUrl target = new TargetUrl("https://example.com/health?secret=hidden");
        ResourceReference reference = new ResourceReference("sensitive-resource-reference");

        assertEquals("[target-url]", target.toString());
        assertEquals("[resource-reference]", reference.toString());
    }
}

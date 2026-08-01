package com.personal.baton.watch.domain.monitoring;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
        assertDoesNotThrow(() -> new TargetUrl("http://example.com:80/health?detail=short"));
        assertDoesNotThrow(() -> new TargetUrl("https://status.example.com:443"));
        String prefix = "https://example.com/";
        assertDoesNotThrow(() -> new TargetUrl(prefix + "a".repeat(TargetUrl.MAX_LENGTH - prefix.length())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TargetUrl(prefix + "a".repeat(TargetUrl.MAX_LENGTH - prefix.length() + 1)));
    }

    @Test
    void rejectsTargetsOutsideTheStaticPolicy() {
        String[] invalidTargets = {
            "/relative",
            "ftp://example.com/file",
            "https://user@example.com/health",
            "https://example.com/health#fragment",
            "https://example.com:8443/health",
            "https://example.com:/health",
            "https://example.com:0443/health",
            "https://127.0.0.1/health",
            "https://[::1]/health",
            "http://2130706433/health",
            "http://0x7f000001/health",
            "https://example.com\\health",
            "https://example.com/health\nnext"
        };

        for (String target : invalidTargets) {
            assertThrows(IllegalArgumentException.class, () -> new TargetUrl(target));
        }
    }
}

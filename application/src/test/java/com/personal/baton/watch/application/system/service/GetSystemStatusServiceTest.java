package com.personal.baton.watch.application.system.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.personal.baton.watch.domain.system.SystemStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class GetSystemStatusServiceTest {

    @Test
    void returnsCurrentServiceStatusUsingTheInjectedClock() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        GetSystemStatusService service = new GetSystemStatusService(Clock.fixed(now, ZoneOffset.UTC));

        SystemStatus result = service.getStatus();

        assertEquals("baton-watch", result.service());
        assertEquals(SystemStatus.State.UP, result.status());
        assertEquals(now, result.observedAt());
    }
}


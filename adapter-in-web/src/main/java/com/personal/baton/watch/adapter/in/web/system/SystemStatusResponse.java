package com.personal.baton.watch.adapter.in.web.system;

import java.time.Instant;

public record SystemStatusResponse(String service, String status, Instant observedAt) {
}

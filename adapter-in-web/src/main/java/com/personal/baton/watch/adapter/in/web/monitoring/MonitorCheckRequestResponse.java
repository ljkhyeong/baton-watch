package com.personal.baton.watch.adapter.in.web.monitoring;

import java.time.Instant;

public record MonitorCheckRequestResponse(String status, Instant nextCheckAt) {}

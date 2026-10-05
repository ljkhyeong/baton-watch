package com.personal.baton.watch.adapter.in.web.system;

import java.time.Clock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public final class SystemStatusController {

    private final Clock clock;

    public SystemStatusController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/status")
    public SystemStatusResponse getStatus() {
        return new SystemStatusResponse("baton-watch", "UP", clock.instant());
    }
}

package com.personal.baton.watch.adapter.in.web.system;

import com.personal.baton.watch.application.system.port.in.GetSystemStatusUseCase;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public final class SystemStatusController {

    private final GetSystemStatusUseCase getSystemStatus;

    public SystemStatusController(GetSystemStatusUseCase getSystemStatus) {
        this.getSystemStatus = Objects.requireNonNull(getSystemStatus, "getSystemStatus");
    }

    @GetMapping("/status")
    public SystemStatusResponse getStatus() {
        return SystemStatusResponse.from(getSystemStatus.getStatus());
    }
}


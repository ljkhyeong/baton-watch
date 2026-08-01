package com.personal.baton.watch.adapter.in.web.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.baton.watch.application.system.port.in.GetSystemStatusUseCase;
import com.personal.baton.watch.domain.system.SystemStatus;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SystemStatusControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GetSystemStatusUseCase useCase = () -> new SystemStatus(
                "baton-watch",
                SystemStatus.State.UP,
                Instant.parse("2026-08-01T00:00:00Z")
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new SystemStatusController(useCase)).build();
    }

    @Test
    void returnsTheVersionedSystemStatusContract() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.service").value("baton-watch"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.observedAt").value("2026-08-01T00:00:00Z"));
    }
}


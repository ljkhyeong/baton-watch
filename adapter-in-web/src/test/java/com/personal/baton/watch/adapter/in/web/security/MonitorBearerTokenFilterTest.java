package com.personal.baton.watch.adapter.in.web.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class MonitorBearerTokenFilterTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController())
                .addFilters(new MonitorBearerTokenFilter("a-test-token"))
                .build();
    }

    @Test
    void protectsMonitorRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/resource-monitors/resource-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void acceptsTheConfiguredBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/resource-monitors/resource-1")
                        .header("Authorization", "Bearer a-test-token"))
                .andExpect(status().isOk());
    }

    @Test
    void leavesUnrelatedRoutesUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk());
    }

    @RestController
    private static final class StubController {

        @GetMapping({"/api/v1/resource-monitors/{resourceReference}", "/api/v1/system/status"})
        String ok() {
            return "ok";
        }
    }
}

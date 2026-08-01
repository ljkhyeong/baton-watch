package com.personal.baton.watch.adapter.in.web.monitoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.baton.watch.adapter.in.web.security.MonitorBearerTokenFilter;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.model.SynchronizationStatus;
import com.personal.baton.watch.application.monitoring.port.in.GetMonitorProjectionUseCase;
import com.personal.baton.watch.application.monitoring.port.in.SynchronizeMonitorUseCase;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ResourceMonitorControllerTest {

    private static final String TOKEN = "test-token";
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    private SynchronizeMonitorUseCase synchronizeMonitor;
    private GetMonitorProjectionUseCase getMonitor;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        synchronizeMonitor = command -> new SynchronizationResult(SynchronizationStatus.APPLIED, projection());
        getMonitor = reference -> Optional.of(projection());
        rebuildMockMvc();
    }

    @Test
    void synchronizesAnActiveMonitorWithoutExposingItsTarget() throws Exception {
        mockMvc.perform(put("/api/v1/resource-monitors/resource-1")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceRevision": 42,
                                  "monitoringState": "ACTIVE",
                                  "targetUrl": "https://example.com/health?secret=hidden"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.resourceReference").value("resource-1"))
                .andExpect(jsonPath("$.sourceRevision").value(42))
                .andExpect(jsonPath("$.monitoringState").value("ACTIVE"))
                .andExpect(jsonPath("$.health").value("UNKNOWN"))
                .andExpect(jsonPath("$.nextCheckAt").value("2026-08-01T00:00:00Z"))
                .andExpect(jsonPath("$.targetUrl").doesNotExist());
    }

    @Test
    void rejectsInvalidTargetsWithAStableProblem() throws Exception {
        mockMvc.perform(put("/api/v1/resource-monitors/resource-1")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceRevision": 42,
                                  "monitoringState": "ACTIVE",
                                  "targetUrl": "http://127.0.0.1/internal"
                                }
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:baton-watch:problem:invalid-target-url"))
                .andExpect(jsonPath("$.code").value("INVALID_TARGET_URL"));
    }

    @Test
    void reportsStaleRevisionsAsConflicts() throws Exception {
        synchronizeMonitor = command -> new SynchronizationResult(SynchronizationStatus.STALE_REVISION, projection());
        rebuildMockMvc();

        mockMvc.perform(put("/api/v1/resource-monitors/resource-1")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceRevision":41,"monitoringState":"INACTIVE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_SOURCE_REVISION"));
    }

    @Test
    void returnsTheCurrentProjection() throws Exception {
        mockMvc.perform(get("/api/v1/resource-monitors/resource-1")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceReference").value("resource-1"))
                .andExpect(jsonPath("$.lastOutcome").doesNotExist())
                .andExpect(jsonPath("$.lastCheckedAt").doesNotExist());
    }

    @Test
    void returnsNotFoundWithoutLeakingTheReference() throws Exception {
        getMonitor = reference -> Optional.empty();
        rebuildMockMvc();

        mockMvc.perform(get("/api/v1/resource-monitors/missing-resource")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MONITOR_NOT_FOUND"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("missing-resource"))));
    }

    private void rebuildMockMvc() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ResourceMonitorController(synchronizeMonitor, getMonitor))
                .setControllerAdvice(new MonitorApiExceptionHandler())
                .addFilters(new MonitorBearerTokenFilter(TOKEN))
                .build();
    }

    private static MonitorProjection projection() {
        return new MonitorProjection(
                new ResourceReference("resource-1"),
                new SourceRevision(42),
                MonitoringState.ACTIVE,
                Health.UNKNOWN,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.of(NOW));
    }
}

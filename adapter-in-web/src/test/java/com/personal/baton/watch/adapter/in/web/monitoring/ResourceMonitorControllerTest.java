package com.personal.baton.watch.adapter.in.web.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.model.SynchronizationStatus;
import com.personal.baton.watch.application.monitoring.port.in.GetMonitorProjectionUseCase;
import com.personal.baton.watch.application.monitoring.port.in.SynchronizeMonitorUseCase;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.HealthDerivation;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(OutputCaptureExtension.class)
class ResourceMonitorControllerTest {

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
        synchronizeMonitor = command -> {
            throw new AssertionError("invalid target reached the synchronization use case");
        };
        rebuildMockMvc();

        mockMvc.perform(put("/api/v1/resource-monitors/resource-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceRevision": 42,
                                  "monitoringState": "ACTIVE",
                                  "targetUrl": "https://example.com/%0d%0aHost:internal"
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceRevision":41,"monitoringState":"INACTIVE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_SOURCE_REVISION"));
    }

    @Test
    void returnsTheCurrentProjection() throws Exception {
        mockMvc.perform(get("/api/v1/resource-monitors/resource-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceReference").value("resource-1"))
                .andExpect(jsonPath("$.lastOutcome").doesNotExist())
                .andExpect(jsonPath("$.lastCheckedAt").doesNotExist());
    }

    @Test
    void returnsNotFoundWithoutLeakingTheReference() throws Exception {
        getMonitor = reference -> Optional.empty();
        rebuildMockMvc();

        mockMvc.perform(get("/api/v1/resource-monitors/missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MONITOR_NOT_FOUND"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("missing-resource"))));
    }

    @Test
    void delegatesPathConversionToSpringWithoutExposingInvalidReferences() throws Exception {
        mockMvc.perform(get("/api/v1/resource-monitors/invalid!reference"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("invalid!reference"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{",
        "{}",
        "{\"sourceRevision\":-1,\"monitoringState\":\"INACTIVE\"}",
        "{\"sourceRevision\":\"invalid\",\"monitoringState\":\"INACTIVE\"}",
        "{\"sourceRevision\":42,\"monitoringState\":\"PAUSED\"}"
    })
    void rejectsMalformedOrInvalidRequestsWithAStableProblem(String body) throws Exception {
        mockMvc.perform(put("/api/v1/resource-monitors/resource-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.aMapWithSize(5)))
                .andExpect(jsonPath("$.type").value("urn:baton-watch:problem:invalid-request"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("urn:baton-watch:request"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsUnsupportedMethodsWithAStableProblem() throws Exception {
        mockMvc.perform(post("/api/v1/resource-monitors/resource-1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(
                        HttpHeaders.ALLOW,
                        org.hamcrest.Matchers.containsString("GET")))
                .andExpect(header().string(
                        HttpHeaders.ALLOW,
                        org.hamcrest.Matchers.containsString("PUT")))
                .andExpect(jsonPath("$.type").value("urn:baton-watch:problem:method-not-allowed"))
                .andExpect(jsonPath("$.title").value("Method not allowed"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void rejectsUnsupportedRequestMediaTypesWithAStableProblem() throws Exception {
        mockMvc.perform(put("/api/v1/resource-monitors/resource-1")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(
                        HttpHeaders.ACCEPT,
                        org.hamcrest.Matchers.containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.type").value("urn:baton-watch:problem:unsupported-media-type"))
                .andExpect(jsonPath("$.title").value("Unsupported media type"))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void rejectsUnacceptableResponseMediaTypesWithAStableProblem() throws Exception {
        mockMvc.perform(get("/api/v1/resource-monitors/resource-1")
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(
                        HttpHeaders.ACCEPT,
                        org.hamcrest.Matchers.containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.type").value("urn:baton-watch:problem:not-acceptable"))
                .andExpect(jsonPath("$.title").value("Not acceptable"))
                .andExpect(jsonPath("$.status").value(406))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void normalizesFrameworkServerErrorsWithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/api/v1/framework-write-failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.aMapWithSize(5)))
                .andExpect(jsonPath("$.type").value("urn:baton-watch:problem:internal-error"))
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.instance").value("urn:baton-watch:request"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void committedFrameworkResponsesDoNotRelogExceptionDetails(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/v1/framework-committed-write-failure"))
                .andExpect(status().isAccepted())
                .andExpect(content().string("already-sent"));

        assertThat(output)
                .contains("monitor API failed failureType=HttpMessageNotWritableException")
                .doesNotContain("raw-output-secret")
                .doesNotContain("Response already committed");
    }

    private void rebuildMockMvc() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ResourceMonitorController(synchronizeMonitor, getMonitor),
                        new FrameworkFailureController())
                .setControllerAdvice(new MonitorApiExceptionHandler())
                .build();
    }

    @RestController
    private static final class FrameworkFailureController {

        @GetMapping("/api/v1/framework-write-failure")
        void writeFailure() {
            throw new HttpMessageNotWritableException("raw-output-secret");
        }

        @GetMapping("/api/v1/framework-committed-write-failure")
        void committedWriteFailure(HttpServletResponse response) throws IOException {
            response.setStatus(HttpStatus.ACCEPTED.value());
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.getWriter().write("already-sent");
            response.flushBuffer();
            throw new HttpMessageNotWritableException("raw-output-secret");
        }
    }

    private static MonitorProjection projection() {
        return new MonitorProjection(
                new ResourceReference("resource-1"),
                new SourceRevision(42),
                MonitoringState.ACTIVE,
                new HealthDerivation(Health.UNKNOWN, 0),
                Optional.empty(),
                Optional.empty(),
                Optional.of(NOW));
    }
}

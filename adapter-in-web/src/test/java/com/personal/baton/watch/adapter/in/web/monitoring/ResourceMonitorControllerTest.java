package com.personal.baton.watch.adapter.in.web.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.model.MonitorCheckRequestResult;
import com.personal.baton.watch.application.monitoring.model.MonitorCheckRequestResult.Status;
import com.personal.baton.watch.application.monitoring.port.in.RequestMonitorCheckUseCase;
import com.personal.baton.watch.application.monitoring.model.SynchronizationStatus;
import com.personal.baton.watch.application.monitoring.port.in.GetMonitorProjectionUseCase;
import com.personal.baton.watch.application.monitoring.port.in.GetMonitorProjectionsUseCase;
import com.personal.baton.watch.application.monitoring.port.in.SynchronizeMonitorUseCase;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.HealthDerivation;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(OutputCaptureExtension.class)
class ResourceMonitorControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    private SynchronizeMonitorUseCase synchronizeMonitor;
    private GetMonitorProjectionUseCase getMonitor;
    private GetMonitorProjectionsUseCase getMonitors;
    private RequestMonitorCheckUseCase requestCheck;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        synchronizeMonitor = command -> new SynchronizationResult(SynchronizationStatus.APPLIED, projection());
        getMonitor = reference -> Optional.of(projection());
        getMonitors = references -> List.of(projection());
        requestCheck = reference -> new MonitorCheckRequestResult(Status.SCHEDULED, NOW, 0);
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
                .andExpect(jsonPath("$.checkStatus").value("QUEUED"))
                .andExpect(jsonPath("$.health").value("UNKNOWN"))
                .andExpect(jsonPath("$.nextCheckAt").value("2026-08-01T00:00:00Z"))
                .andExpect(jsonPath("$.targetUrl").doesNotExist());
    }

    @ParameterizedTest
    @CsvSource({
        "INACTIVE, , , INACTIVE",
        "ACTIVE, 30, , SCHEDULED",
        "ACTIVE, 0, , QUEUED",
        "ACTIVE, 0, 30, IN_PROGRESS"
    })
    void exposesCheckStatusWithoutExposingLeaseDetails(
            MonitoringState state, Long nextOffset, Long leaseOffset, String expected) throws Exception {
        getMonitor = reference -> Optional.of(projection(state, nextOffset, leaseOffset));
        rebuildMockMvc();

        mockMvc.perform(get("/api/v1/resource-monitors/resource-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.monitoringState").value(state.name()))
                .andExpect(jsonPath("$.checkStatus").value(expected))
                .andExpect(jsonPath("$.health").value("UNKNOWN"))
                .andExpect(jsonPath("$.leaseExpiresAt").doesNotExist())
                .andExpect(jsonPath("$.leaseToken").doesNotExist())
                .andExpect(jsonPath("$.leaseAttemptId").doesNotExist())
                .andExpect(jsonPath("$.targetUrl").doesNotExist());
    }

    @Test
    void batchLookupPreservesRequestOrderAndReportsMissingReferencesOnce() throws Exception {
        getMonitors = references -> {
            assertThat(references).extracting(ResourceReference::value)
                    .containsExactly("resource-1", "missing", "resource-2", "resource-1", "missing");
            return List.of(
                    projection("resource-2", MonitoringState.INACTIVE, null, null),
                    projection("resource-1", MonitoringState.ACTIVE, 0L, 30L));
        };
        rebuildMockMvc();

        mockMvc.perform(get("/api/v1/resource-monitors")
                        .param("resourceReference", "resource-1", "missing", "resource-2", "resource-1", "missing"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.monitors.length()").value(2))
                .andExpect(jsonPath("$.monitors[0].resourceReference").value("resource-1"))
                .andExpect(jsonPath("$.monitors[0].checkStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.monitors[0].health").value("UNKNOWN"))
                .andExpect(jsonPath("$.monitors[0].leaseExpiresAt").doesNotExist())
                .andExpect(jsonPath("$.monitors[0].leaseToken").doesNotExist())
                .andExpect(jsonPath("$.monitors[0].targetUrl").doesNotExist())
                .andExpect(jsonPath("$.monitors[1].resourceReference").value("resource-2"))
                .andExpect(jsonPath("$.monitors[1].checkStatus").value("INACTIVE"))
                .andExpect(jsonPath("$.missingResourceReferences", contains("missing")));
    }

    @Test
    void batchLookupReturnsEmptyMonitorsWhenAllReferencesAreMissing() throws Exception {
        getMonitors = references -> List.of();
        rebuildMockMvc();

        mockMvc.perform(get("/api/v1/resource-monitors").param("resourceReference", "missing-1", "missing-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monitors").isEmpty())
                .andExpect(jsonPath("$.missingResourceReferences", contains("missing-1", "missing-2")));
    }

    @Test
    void rejectsBatchLookupWithoutReferences() throws Exception {
        getMonitors = references -> {
            throw new AssertionError("조회 대상 없는 요청이 유스케이스에 도달했습니다");
        };
        rebuildMockMvc();

        mockMvc.perform(get("/api/v1/resource-monitors"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @MethodSource("invalidBatchReferences")
    void rejectsInvalidBatchReferencesBeforeLookup(String[] references) throws Exception {
        getMonitors = ignored -> {
            throw new AssertionError("잘못된 조회 대상이 유스케이스에 도달했습니다");
        };
        rebuildMockMvc();

        mockMvc.perform(get("/api/v1/resource-monitors").param("resourceReference", references))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.aMapWithSize(5)))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.instance").value("urn:baton-watch:request"));
    }

    private static Stream<Arguments> invalidBatchReferences() {
        return Stream.of(
                Arguments.of((Object) new String[] {""}),
                Arguments.of((Object) new String[] {"resource-1", ""}),
                Arguments.of((Object) new String[] {"resource-1", "raw-reference-secret/invalid"}),
                Arguments.of((Object) new String[] {"r".repeat(129)}),
                Arguments.of((Object) IntStream.range(0, 21).mapToObj(index -> "resource-1").toArray(String[]::new)));
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
                .andExpect(jsonPath("$", org.hamcrest.Matchers.aMapWithSize(5)))
                .andExpect(jsonPath("$.type").value("urn:baton-watch:problem:invalid-target-url"))
                .andExpect(jsonPath("$.title").value("점검할 수 없는 URL입니다"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.instance").value("urn:baton-watch:request"))
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
    void reportsSameRevisionWithDifferentDataAsAConflict() throws Exception {
        synchronizeMonitor = command -> new SynchronizationResult(SynchronizationStatus.REVISION_CONFLICT, projection());
        rebuildMockMvc();

        mockMvc.perform(put("/api/v1/resource-monitors/resource-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceRevision": 42,
                                  "monitoringState": "ACTIVE",
                                  "targetUrl": "https://example.com/health?secret=hidden"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type")
                        .value("urn:baton-watch:problem:source-revision-conflict"))
                .andExpect(jsonPath("$.code").value("SOURCE_REVISION_CONFLICT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret=hidden"))));
    }

    @Test
    void returnsTheCurrentProjection() throws Exception {
        mockMvc.perform(get("/api/v1/resource-monitors/resource-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceReference").value("resource-1"))
                .andExpect(jsonPath("$.lastOutcome").doesNotExist())
                .andExpect(jsonPath("$.lastCheckedAt").doesNotExist())
                .andExpect(jsonPath("$.lastConclusiveAt").value("2026-07-31T23:59:00Z"));
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
                .andExpect(jsonPath("$.title").value("요청 형식이 올바르지 않습니다"))
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
                .andExpect(jsonPath("$.title").value("지원하지 않는 HTTP 메서드입니다"))
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
                .andExpect(jsonPath("$.title").value("지원하지 않는 요청 본문 형식입니다"))
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
                .andExpect(jsonPath("$.title").value("요청한 응답 형식을 지원하지 않습니다"))
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
                .andExpect(jsonPath("$.title").value("요청 처리 중 서버 오류가 발생했습니다"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.instance").value("urn:baton-watch:request"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("temporaryFailures")
    void reportsTemporaryFailuresWithoutRetryingOrLeakingDetails(
            RuntimeException failure, CapturedOutput output) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        getMonitor = reference -> {
            calls.incrementAndGet();
            throw failure;
        };
        rebuildMockMvc();

        mockMvc.perform(get("/api/v1/resource-monitors/resource-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.aMapWithSize(5)))
                .andExpect(jsonPath("$.type").value("urn:baton-watch:problem:service-unavailable"))
                .andExpect(jsonPath("$.title").value("일시적으로 요청을 처리할 수 없습니다"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.instance").value("urn:baton-watch:request"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(output).contains("failureType=" + failure.getClass().getSimpleName())
                .doesNotContain("raw-storage-secret", "raw-sql-secret");
    }

    private static Stream<RuntimeException> temporaryFailures() {
        return Stream.of(
                new QueryTimeoutException("raw-storage-secret"),
                new CannotAcquireLockException("raw-storage-secret"),
                new TransientDataAccessResourceException("raw-storage-secret"),
                new DataAccessResourceFailureException("raw-storage-secret"),
                new RecoverableDataAccessException("raw-storage-secret"),
                new TransactionTimedOutException("raw-storage-secret"),
                new CannotCreateTransactionException("raw-storage-secret", new SQLException("raw-sql-secret")));
    }

    @ParameterizedTest
    @MethodSource("nonTemporaryFailures")
    void keepsProgrammingAndIntegrityFailuresAsInternalErrors(RuntimeException failure) throws Exception {
        getMonitor = reference -> {
            throw failure;
        };
        rebuildMockMvc();

        mockMvc.perform(get("/api/v1/resource-monitors/resource-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    private static Stream<RuntimeException> nonTemporaryFailures() {
        return Stream.of(
                new DataIntegrityViolationException("raw-storage-secret"),
                new InvalidDataAccessApiUsageException("raw-storage-secret"),
                new IllegalStateException("raw-storage-secret"),
                new CannotCreateTransactionException("raw-storage-secret", new IllegalArgumentException()),
                new NestedTransactionNotSupportedException("raw-storage-secret"));
    }

    @Test
    void committedFrameworkResponsesDoNotRelogExceptionDetails(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/v1/framework-committed-write-failure"))
                .andExpect(status().isAccepted())
                .andExpect(content().string("already-sent"));

        assertThat(output)
                .contains("모니터 API 처리 실패 failureType=HttpMessageNotWritableException")
                .doesNotContain("raw-output-secret")
                .doesNotContain("Response already committed");
    }

    @Test
    void acceptsAndMergesCheckRequestsWithoutReturningTargetData() throws Exception {
        for (Status resultStatus : new Status[] {
                Status.SCHEDULED, Status.ALREADY_SCHEDULED, Status.IN_PROGRESS}) {
            requestCheck = reference -> new MonitorCheckRequestResult(
                    resultStatus, resultStatus == Status.IN_PROGRESS ? null : NOW, 0);
            rebuildMockMvc();
            mockMvc.perform(post("/api/v1/resource-monitors/resource-1/check-requests"))
                    .andExpect(status().isAccepted())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(resultStatus.name()))
                    .andExpect(jsonPath("$.targetUrl").doesNotExist());
        }
    }

    @Test
    void rejectsMissingInactiveAndTooFrequentCheckRequests() throws Exception {
        requestCheck = reference -> new MonitorCheckRequestResult(Status.NOT_FOUND, null, 0);
        rebuildMockMvc();
        mockMvc.perform(post("/api/v1/resource-monitors/missing/check-requests"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MONITOR_NOT_FOUND"));

        requestCheck = reference -> new MonitorCheckRequestResult(Status.INACTIVE, null, 0);
        rebuildMockMvc();
        mockMvc.perform(post("/api/v1/resource-monitors/resource-1/check-requests"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITOR_INACTIVE"));

        requestCheck = reference -> new MonitorCheckRequestResult(Status.RATE_LIMITED, null, 12);
        rebuildMockMvc();
        mockMvc.perform(post("/api/v1/resource-monitors/resource-1/check-requests"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "12"))
                .andExpect(jsonPath("$.code").value("CHECK_REQUEST_RATE_LIMITED"))
                .andExpect(jsonPath("$.instance").value("urn:baton-watch:request"));
    }

    private void rebuildMockMvc() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ResourceMonitorController(
                                synchronizeMonitor, getMonitor, getMonitors, requestCheck,
                                Clock.fixed(NOW, ZoneOffset.UTC)),
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
        return projection(MonitoringState.ACTIVE, 0L, null);
    }

    private static MonitorProjection projection(MonitoringState state, Long nextOffset, Long leaseOffset) {
        return projection("resource-1", state, nextOffset, leaseOffset);
    }

    private static MonitorProjection projection(
            String reference, MonitoringState state, Long nextOffset, Long leaseOffset) {
        return new MonitorProjection(
                new ResourceReference(reference),
                new SourceRevision(42),
                state,
                new HealthDerivation(Health.UNKNOWN, 0),
                Optional.empty(),
                Optional.empty(),
                Optional.of(NOW.minusSeconds(60)),
                Optional.ofNullable(nextOffset).map(NOW::plusSeconds),
                Optional.ofNullable(leaseOffset).map(NOW::plusSeconds));
    }
}

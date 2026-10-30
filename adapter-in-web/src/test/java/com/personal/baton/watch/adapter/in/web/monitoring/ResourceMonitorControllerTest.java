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
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(OutputCaptureExtension.class)
class ResourceMonitorControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    private SynchronizeMonitorUseCase synchronizeMonitor;
    private GetMonitorProjectionsUseCase getMonitors;
    private RequestMonitorCheckUseCase requestCheck;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        synchronizeMonitor = command -> new SynchronizationResult(SynchronizationStatus.APPLIED, projection());
        getMonitors = references -> List.of(projection());
        requestCheck = reference -> new MonitorCheckRequestResult(Status.SCHEDULED, NOW, 0);
        // 메서드 참조는 지금 대입된 대체 구현을 고정하므로, 테스트별 교체가 반영되도록 호출 시점에 필드를 읽는다.
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ResourceMonitorController(
                                command -> synchronizeMonitor.synchronize(command),
                                references -> getMonitors.get(references),
                                reference -> requestCheck.requestCheck(reference),
                                Clock.fixed(NOW, ZoneOffset.UTC)),
                        new FrameworkFailureController())
                .setControllerAdvice(new MonitorApiExceptionHandler(), new MonitorApiRequestBodyLimit())
                .build();
    }

    @Test
    void synchronizesAnActiveMonitorWithoutExposingItsTarget() throws Exception {
        synchronizeMonitor = command -> {
            assertThat(command.targetUrl().orElseThrow().value())
                    .isEqualTo("https://example.com/자료/\uD83D\uDE00?secret=hidden");
            return new SynchronizationResult(SynchronizationStatus.APPLIED, projection());
        };

        mockMvc.perform(put("/api/v1/resource-monitors/resource-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceRevision": 42,
                                  "monitoringState": "ACTIVE",
                                  "targetUrl": "https://example.com/자료/\\uD83D\\uDE00?secret=hidden"
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

    @Test
    void batchLookupPreservesRequestOrderAndReportsMissingReferencesOnce() throws Exception {
        getMonitors = references -> {
            assertThat(references).extracting(ResourceReference::value)
                    .containsExactly("resource-1", "missing", "resource-2", "resource-1", "missing");
            return List.of(
                    projection("resource-2", MonitoringState.INACTIVE, null, null),
                    projection("resource-1", MonitoringState.ACTIVE, 0L, 30L));
        };

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
                .andExpect(jsonPath("$.monitors[0].leaseAttemptId").doesNotExist())
                .andExpect(jsonPath("$.monitors[0].targetUrl").doesNotExist())
                .andExpect(jsonPath("$.monitors[1].resourceReference").value("resource-2"))
                .andExpect(jsonPath("$.monitors[1].checkStatus").value("INACTIVE"))
                .andExpect(jsonPath("$.missingResourceReferences", contains("missing")));
    }

    @Test
    void batchLookupReturnsEmptyMonitorsWhenAllReferencesAreMissing() throws Exception {
        getMonitors = references -> List.of();

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

        mockMvc.perform(get("/api/v1/resource-monitors"))
                .andExpect(problem(HttpStatus.BAD_REQUEST, "invalid-request", "요청 형식이 올바르지 않습니다",
                        "INVALID_REQUEST"));
    }

    @ParameterizedTest
    @MethodSource("invalidBatchReferences")
    void rejectsInvalidBatchReferencesBeforeLookup(String[] references) throws Exception {
        getMonitors = ignored -> {
            throw new AssertionError("잘못된 조회 대상이 유스케이스에 도달했습니다");
        };

        mockMvc.perform(get("/api/v1/resource-monitors").param("resourceReference", references))
                .andExpect(problem(HttpStatus.BAD_REQUEST, "invalid-request", "요청 형식이 올바르지 않습니다",
                        "INVALID_REQUEST"));
    }

    private static Stream<Arguments> invalidBatchReferences() {
        return Stream.of(
                Arguments.of((Object) new String[] {""}),
                Arguments.of((Object) new String[] {"resource-1", ""}),
                Arguments.of((Object) new String[] {"resource-1", "raw-reference-secret/invalid"}),
                Arguments.of((Object) new String[] {"r".repeat(129)}),
                Arguments.of((Object) IntStream.range(0, 21).mapToObj(index -> "resource-1").toArray(String[]::new)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.com/%0d%0aHost:internal",
        "https://example.com/\\uD800",
        "https://example.com/\\uDC00",
        "https://example.com/?query=\\uDFFF"
    })
    void rejectsInvalidTargetsWithAStableProblem(String target) throws Exception {
        synchronizeMonitor = command -> {
            throw new AssertionError("invalid target reached the synchronization use case");
        };

        mockMvc.perform(put("/api/v1/resource-monitors/resource-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceRevision": 42,
                                  "monitoringState": "ACTIVE",
                                  "targetUrl": "%s"
                                }
                                """.formatted(target)))
                .andExpect(problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-target-url", "점검할 수 없는 URL입니다",
                        "INVALID_TARGET_URL"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "STALE_REVISION | {\"sourceRevision\":41,\"monitoringState\":\"INACTIVE\"}"
                + " | stale-source-revision | 저장된 리비전보다 오래된 요청입니다 | STALE_SOURCE_REVISION",
        "REVISION_CONFLICT | {\"sourceRevision\":42,\"monitoringState\":\"ACTIVE\","
                + "\"targetUrl\":\"https://example.com/health?secret=hidden\"}"
                + " | source-revision-conflict | 같은 리비전에 다른 내용이 등록되어 있습니다 | SOURCE_REVISION_CONFLICT"
    })
    void reportsStaleOrDifferentSameRevisionsAsConflicts(
            SynchronizationStatus result, String body, String slug, String title, String code) throws Exception {
        synchronizeMonitor = command -> new SynchronizationResult(result, projection());

        mockMvc.perform(put("/api/v1/resource-monitors/resource-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(problem(HttpStatus.CONFLICT, slug, title, code))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret=hidden"))));
    }

    @Test
    void returnsTheCurrentProjection() throws Exception {
        getMonitors = references -> {
            assertThat(references).extracting(ResourceReference::value).containsExactly("resource-1");
            return List.of(projection(MonitoringState.ACTIVE, 30L, null));
        };

        mockMvc.perform(get("/api/v1/resource-monitors/resource-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceReference").value("resource-1"))
                .andExpect(jsonPath("$.checkStatus").value("SCHEDULED"))
                .andExpect(jsonPath("$.lastOutcome").doesNotExist())
                .andExpect(jsonPath("$.lastCheckedAt").doesNotExist())
                .andExpect(jsonPath("$.lastConclusiveAt").value("2026-07-31T23:59:00Z"));
    }

    @Test
    void returnsNotFoundWithoutLeakingTheReference() throws Exception {
        getMonitors = references -> List.of();

        mockMvc.perform(get("/api/v1/resource-monitors/missing-resource"))
                .andExpect(problem(HttpStatus.NOT_FOUND, "monitor-not-found", "등록된 점검 대상이 없습니다",
                        "MONITOR_NOT_FOUND"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("missing-resource"))));
    }

    @Test
    void delegatesPathConversionToSpringWithoutExposingInvalidReferences() throws Exception {
        mockMvc.perform(get("/api/v1/resource-monitors/invalid!reference"))
                .andExpect(problem(HttpStatus.BAD_REQUEST, "invalid-request", "요청 형식이 올바르지 않습니다",
                        "INVALID_REQUEST"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("invalid!reference"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{",
        "{}",
        "{\"sourceRevision\":-1,\"monitoringState\":\"INACTIVE\"}",
        "{\"sourceRevision\":\"invalid\",\"monitoringState\":\"INACTIVE\"}",
        "{\"sourceRevision\":42,\"monitoringState\":\"PAUSED\"}",
        "{\"sourceRevision\":42,\"monitoringState\":\"ACTIVE\"}",
        "{\"sourceRevision\":42,\"monitoringState\":\"INACTIVE\",\"targetUrl\":\"https://example.com/health\"}"
    })
    void rejectsMalformedOrInvalidRequestsWithAStableProblem(String body) throws Exception {
        mockMvc.perform(put("/api/v1/resource-monitors/resource-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(problem(HttpStatus.BAD_REQUEST, "invalid-request", "요청 형식이 올바르지 않습니다",
                        "INVALID_REQUEST"));
    }

    @Test
    void normalizesFrameworkServerErrorsWithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/api/v1/framework-write-failure"))
                .andExpect(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "요청 처리 중 서버 오류가 발생했습니다",
                        "INTERNAL_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("temporaryFailures")
    void reportsTemporaryFailuresWithoutRetryingOrLeakingDetails(
            RuntimeException failure, CapturedOutput output) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        getMonitors = references -> {
            calls.incrementAndGet();
            throw failure;
        };

        mockMvc.perform(get("/api/v1/resource-monitors/resource-1"))
                .andExpect(problem(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable",
                        "일시적으로 요청을 처리할 수 없습니다", "SERVICE_UNAVAILABLE"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"));

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
        getMonitors = references -> {
            throw failure;
        };

        mockMvc.perform(get("/api/v1/resource-monitors/resource-1"))
                .andExpect(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "요청 처리 중 서버 오류가 발생했습니다",
                        "INTERNAL_ERROR"))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
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

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"SCHEDULED", "ALREADY_SCHEDULED", "IN_PROGRESS"})
    void acceptsAndMergesCheckRequestsWithoutReturningTargetData(Status resultStatus) throws Exception {
        requestCheck = reference -> new MonitorCheckRequestResult(
                resultStatus, resultStatus == Status.IN_PROGRESS ? null : NOW, 0);

        mockMvc.perform(post("/api/v1/resource-monitors/resource-1/check-requests"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(resultStatus.name()))
                .andExpect(jsonPath("$.targetUrl").doesNotExist());
    }

    @Test
    void rejectsMissingInactiveAndTooFrequentCheckRequests() throws Exception {
        requestCheck = reference -> new MonitorCheckRequestResult(Status.NOT_FOUND, null, 0);
        mockMvc.perform(post("/api/v1/resource-monitors/missing/check-requests"))
                .andExpect(problem(HttpStatus.NOT_FOUND, "monitor-not-found", "등록된 점검 대상이 없습니다",
                        "MONITOR_NOT_FOUND"))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));

        requestCheck = reference -> new MonitorCheckRequestResult(Status.INACTIVE, null, 0);
        mockMvc.perform(post("/api/v1/resource-monitors/resource-1/check-requests"))
                .andExpect(problem(HttpStatus.CONFLICT, "monitor-inactive", "비활성 점검 대상은 재점검할 수 없습니다",
                        "MONITOR_INACTIVE"))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));

        requestCheck = reference -> new MonitorCheckRequestResult(Status.RATE_LIMITED, null, 12);
        mockMvc.perform(post("/api/v1/resource-monitors/resource-1/check-requests"))
                .andExpect(problem(HttpStatus.TOO_MANY_REQUESTS, "check-request-rate-limited",
                        "재점검 요청 간격이 너무 짧습니다", "CHECK_REQUEST_RATE_LIMITED"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "12"));
    }

    private static ResultMatcher problem(HttpStatus httpStatus, String slug, String title, String code) {
        String body = """
                {"type":"urn:baton-watch:problem:%s","title":"%s","status":%d,\
                "instance":"urn:baton-watch:request","code":"%s"}"""
                .formatted(slug, title, httpStatus.value(), code);
        return result -> {
            status().is(httpStatus.value()).match(result);
            content().contentType(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            content().json(body, JsonCompareMode.STRICT).match(result);
        };
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

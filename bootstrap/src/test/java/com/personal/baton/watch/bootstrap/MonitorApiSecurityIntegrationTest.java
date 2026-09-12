package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.watch.adapter.in.web.monitoring.MonitorApiExceptionHandler;
import com.personal.baton.watch.adapter.in.web.monitoring.ResourceMonitorController;
import com.personal.baton.watch.adapter.in.web.security.MonitorApiRequestBodyLimitFilter;
import com.personal.baton.watch.adapter.in.web.system.SystemStatusController;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.model.MonitorCheckRequestResult;
import com.personal.baton.watch.application.monitoring.port.in.RequestMonitorCheckUseCase;
import com.personal.baton.watch.application.monitoring.model.SynchronizationStatus;
import com.personal.baton.watch.application.monitoring.port.in.GetMonitorProjectionUseCase;
import com.personal.baton.watch.application.monitoring.port.in.GetMonitorProjectionsUseCase;
import com.personal.baton.watch.application.monitoring.port.in.SynchronizeMonitorUseCase;
import com.personal.baton.watch.application.system.port.in.GetSystemStatusUseCase;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.HealthDerivation;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.system.SystemStatus;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = MonitorApiSecurityIntegrationTest.TestApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "server.servlet.context-path=/watch",
            "management.server.port=-1",
            // DB를 제외한 보안 전용 조립이므로 기본 준비 상태만 사용한다.
            "management.endpoint.health.group.readiness.include=readinessState",
            "server.max-http-request-header-size=64KB",
            "server.tomcat.max-http-response-header-size=64KB",
            "server.tomcat.max-connections=4096",
            "server.tomcat.accept-count=1024",
            "server.tomcat.threads.max=512",
            "server.tomcat.threads.min-spare=64",
            "server.tomcat.threads.max-queue-capacity=4096"
        })
class MonitorApiSecurityIntegrationTest {

    private static final String API_TOKEN = "monitor-api-token-0123456789-abcdef";
    private static final String CONTEXT_PATH = "/watch";
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @LocalServerPort
    private int serverPort;

    private final ObjectMapper objectMapper;
    private final ServletWebServerApplicationContext applicationContext;

    @Autowired
    MonitorApiSecurityIntegrationTest(
            ObjectMapper objectMapper,
            ServletWebServerApplicationContext applicationContext) {
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
    }

    @Test
    void externalConfigurationCannotRelaxEmbeddedTomcatResourceBounds() {
        var webServer = (TomcatWebServer) applicationContext.getWebServer();
        var connector = webServer.getTomcat().getConnector();
        var protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();

        assertThat(protocol.getMaxHttpRequestHeaderSize()).isEqualTo(8 * 1024);
        assertThat(protocol.getMaxHttpResponseHeaderSize()).isEqualTo(8 * 1024);
        assertThat(protocol.getMaxConnections()).isEqualTo(128);
        assertThat(protocol.getAcceptCount()).isEqualTo(32);
        assertThat(protocol.getMaxThreads()).isEqualTo(32);
        assertThat(protocol.getMinSpareThreads()).isEqualTo(4);
        assertThat(protocol.getMaxQueueSize()).isEqualTo(64);
    }

    @Test
    void servletContextPathCannotBypassMonitorAuthentication() throws Exception {
        HttpResponse<String> missing = get("/api/v1/resource-monitors/resource-1", null);
        HttpResponse<String> wrong = get("/api/v1/resource-monitors/resource-1", "wrong-token");
        HttpResponse<String> valid = get("/api/v1/resource-monitors/resource-1", API_TOKEN);

        assertUnauthorized(missing);
        assertUnauthorized(wrong);
        assertThat(valid.statusCode()).isEqualTo(200);
        assertThat(valid.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(objectMapper.readTree(valid.body()).path("checkStatus").asString()).isEqualTo("INACTIVE");
    }

    @Test
    void batchLookupRequiresAuthenticationAndReportsMissingReferences() throws Exception {
        String path = "/api/v1/resource-monitors?resourceReference=resource-1&resourceReference=missing";
        assertUnauthorized(get(path, null));
        assertUnauthorized(get(path, "wrong-token"));
        HttpResponse<String> response = get(path, API_TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("monitors").get(0).path("resourceReference").asString()).isEqualTo("resource-1");
        assertThat(body.path("monitors").get(0).path("checkStatus").asString()).isEqualTo("INACTIVE");
        assertThat(body.path("missingResourceReferences").get(0).asString()).isEqualTo("missing");
        assertThat(response.body()).doesNotContain("leaseToken", "leaseExpiresAt", "targetUrl");
        assertHeaderContains(response, HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @Test
    void batchLookupAcceptsTwentyMaximumLengthReferencesAndRejectsMoreAfterAuthentication() throws Exception {
        String query = IntStream.range(0, 20)
                .mapToObj(index -> "resourceReference=" + "r".repeat(126) + String.format("%02d", index))
                .collect(Collectors.joining("&"));
        String path = "/api/v1/resource-monitors?" + query;
        HttpResponse<String> maximum = get(path, API_TOKEN);
        assertThat(maximum.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(maximum.body()).path("missingResourceReferences").size()).isEqualTo(20);

        String tooMany = path + "&resourceReference=resource-1";
        assertUnauthorized(get(tooMany, null));
        assertProblem(get(tooMany, API_TOKEN), 400, "urn:baton-watch:problem:invalid-request",
                "요청 형식이 올바르지 않습니다", "INVALID_REQUEST");
    }

    @Test
    void springBearerResolverHandlesTheSchemeAndRejectsMalformedCredentials() throws Exception {
        HttpResponse<String> lowercase = getWithAuthorization(
                "/api/v1/resource-monitors/resource-1", "bearer " + API_TOKEN);
        HttpResponse<String> invalidCharacters = getWithAuthorization(
                "/api/v1/resource-monitors/resource-1",
                "Bearer monitor:api:token:0123456789:abcdef");
        HttpResponse<String> combinedCredentials = getWithAuthorization(
                "/api/v1/resource-monitors/resource-1", "Bearer " + API_TOKEN + ", other");

        assertThat(lowercase.statusCode()).isEqualTo(200);
        assertUnauthorized(invalidCharacters);
        assertUnauthorized(combinedCredentials);
        assertThat(invalidCharacters.body()).doesNotContain("monitor:api:token");
        assertThat(combinedCredentials.body()).doesNotContain(API_TOKEN);
    }

    @Test
    void exactSystemStatusGetRemainsPublicEvenWithAnInvalidBearerHeader() throws Exception {
        HttpResponse<String> response = get("/api/v1/system/status", "wrong-token");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"wrong-token", API_TOKEN})
    void exactSystemStatusHeadIsPublicAndReturnsOnlyHeaders(String token) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/v1/system/status"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()), token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
        assertHeaderContains(response, HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        assertHeaderContains(response, HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/resource-monitors/resource-1",
        "/api/v1/system/status/",
        "/api/v1/system/status/extra"
    })
    void headOnOtherPathsStillRequiresAuthentication(String path) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()), null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).contains("Bearer");
    }

    @Test
    void authenticationPrecedesRequestBodyParsingAndPutDoesNotRequireCsrf() throws Exception {
        HttpResponse<String> missing = put("/api/v1/resource-monitors/resource-1", null, "{");
        HttpResponse<String> malformed = put("/api/v1/resource-monitors/resource-1", API_TOKEN, "{");
        HttpResponse<String> valid = put(
                "/api/v1/resource-monitors/resource-1",
                API_TOKEN,
                "{\"sourceRevision\":42,\"monitoringState\":\"INACTIVE\"}");

        assertUnauthorized(missing);
        assertProblem(
                malformed,
                400,
                "urn:baton-watch:problem:invalid-request",
                "요청 형식이 올바르지 않습니다",
                "INVALID_REQUEST");
        assertThat(valid.statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.5", "42.9", "42.0", "4.2e1", "\"42\"", "\"0\"", "\"9223372036854775807\""})
    void rejectsNonIntegerRevisionTokensBeforeSynchronization(String revision) throws Exception {
        String path = "/api/v1/resource-monitors/storage-unavailable";
        String body = "{\"sourceRevision\":" + revision + ",\"monitoringState\":\"INACTIVE\"}";

        assertUnauthorized(put(path, null, body));
        assertProblem(put(path, API_TOKEN, body), 400, "urn:baton-watch:problem:invalid-request",
                "요청 형식이 올바르지 않습니다", "INVALID_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"sourceRevision\":42,\"monitoringState\":0,\"targetUrl\":\"https://example.com/health\"}",
        "{\"sourceRevision\":42,\"monitoringState\":1}",
        "{\"sourceRevision\":42,\"monitoringState\":\"0\",\"targetUrl\":\"https://example.com/health\"}",
        "{\"sourceRevision\":42,\"monitoringState\":\"1\"}"
    })
    void rejectsNumericMonitoringStatesBeforeSynchronization(String body) throws Exception {
        String path = "/api/v1/resource-monitors/storage-unavailable";

        assertUnauthorized(put(path, null, body));
        assertProblem(put(path, API_TOKEN, body), 400, "urn:baton-watch:problem:invalid-request",
                "요청 형식이 올바르지 않습니다", "INVALID_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"sourceRevision\":41,\"sourceRevision\":42,\"monitoringState\":\"INACTIVE\"}",
        "{\"sourceRevision\":42,\"monitoringState\":\"ACTIVE\",\"monitoringState\":\"INACTIVE\"}",
        "{\"sourceRevision\":42,\"monitoringState\":\"ACTIVE\",\"targetUrl\":\"https://example.com/first\",\"targetUrl\":\"https://example.com/second\"}",
        "{\"sourceRevision\":42,\"sourceRevision\":42,\"monitoringState\":\"INACTIVE\"}",
        "{\"sourceRevision\":41,\"source\\u0052evision\":42,\"monitoringState\":\"INACTIVE\"}"
    })
    void rejectsDuplicateJsonFieldsBeforeSynchronization(String body) throws Exception {
        String path = "/api/v1/resource-monitors/storage-unavailable";

        assertUnauthorized(put(path, null, body));
        assertProblem(put(path, API_TOKEN, body), 400, "urn:baton-watch:problem:invalid-request",
                "요청 형식이 올바르지 않습니다", "INVALID_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, Long.MAX_VALUE})
    void acceptsIntegerRevisionBoundaries(long revision) throws Exception {
        HttpResponse<String> response = put("/api/v1/resource-monitors/resource-1", API_TOKEN,
                "{\"sourceRevision\":" + revision + ",\"monitoringState\":\"INACTIVE\"}");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void authenticationPrecedesContentLengthAndChunkedBodyLimits() throws Exception {
        String oversizedBody = "x".repeat(MonitorApiRequestBodyLimitFilter.MAX_REQUEST_BODY_BYTES + 1);
        HttpRequest.BodyPublisher chunkedBody = chunked(oversizedBody);

        HttpResponse<String> contentLength = put(
                "/api/v1/resource-monitors/resource-1",
                null,
                MediaType.APPLICATION_JSON_VALUE,
                HttpRequest.BodyPublishers.ofString(oversizedBody));
        HttpResponse<String> chunked = put(
                "/api/v1/resource-monitors/resource-1",
                null,
                MediaType.APPLICATION_JSON_VALUE,
                chunkedBody);

        assertUnauthorized(contentLength);
        assertUnauthorized(chunked);
    }

    @Test
    void authenticatedContentLengthAndChunkedBodiesAboveTheLimitReturnStableProblems() throws Exception {
        String oversizedBody = "x".repeat(MonitorApiRequestBodyLimitFilter.MAX_REQUEST_BODY_BYTES + 1);
        HttpRequest.BodyPublisher declaredBody = HttpRequest.BodyPublishers.ofString(oversizedBody);
        HttpRequest.BodyPublisher chunkedBody = chunked(oversizedBody);

        HttpResponse<String> contentLength = put(
                "/api/v1/resource-monitors/resource-1",
                API_TOKEN,
                MediaType.APPLICATION_JSON_VALUE,
                declaredBody);
        HttpResponse<String> chunked = put(
                "/api/v1/resource-monitors/resource-1",
                API_TOKEN,
                MediaType.APPLICATION_JSON_VALUE,
                chunkedBody);

        assertProblem(
                contentLength,
                413,
                "urn:baton-watch:problem:payload-too-large",
                "요청 본문이 허용 크기를 초과했습니다",
                "PAYLOAD_TOO_LARGE");
        assertProblem(
                chunked,
                413,
                "urn:baton-watch:problem:payload-too-large",
                "요청 본문이 허용 크기를 초과했습니다",
                "PAYLOAD_TOO_LARGE");
    }

    @Test
    void acceptsAJsonBodyAtTheExactByteLimit() throws Exception {
        String json = "{\"sourceRevision\":42,\"monitoringState\":\"INACTIVE\"}";
        String body = json + " ".repeat(
                MonitorApiRequestBodyLimitFilter.MAX_REQUEST_BODY_BYTES
                        - json.getBytes(StandardCharsets.UTF_8).length);

        HttpResponse<String> response = put(
                "/api/v1/resource-monitors/resource-1",
                API_TOKEN,
                MediaType.APPLICATION_JSON_VALUE,
                HttpRequest.BodyPublishers.ofString(body));

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void everyOtherVersionedApiRequestFailsClosed() throws Exception {
        HttpResponse<String> apiRoot = get("/api/v1", null);
        HttpResponse<String> statusPost = post("/api/v1/system/status", null);
        HttpResponse<String> missing = get("/api/v1/future-route", null);
        HttpResponse<String> authenticated = get("/api/v1/future-route", API_TOKEN);

        assertUnauthorized(apiRoot);
        assertUnauthorized(statusPost);
        assertUnauthorized(missing);
        assertProblem(
                authenticated,
                404,
                "urn:baton-watch:problem:route-not-found",
                "요청한 API 경로가 없습니다",
                "ROUTE_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/resource-monitors/resource-1;secret=raw-value",
        "/api//v1/resource-monitors/resource-1",
        "/api/v1/resource-monitors//resource-1"
    })
    void httpFirewallRejectionsUseAStableRedactedProblem(String path) throws Exception {
        HttpResponse<String> response = get(path, null);

        assertProblem(
                response,
                400,
                "urn:baton-watch:problem:request-rejected",
                "허용되지 않는 HTTP 요청입니다",
                "REQUEST_REJECTED");
        assertThat(response.body())
                .doesNotContain("raw-value")
                .doesNotContain("resource-1")
                .doesNotContain(CONTEXT_PATH)
                .doesNotContain(path);
    }

    @Test
    void authenticatedFrameworkErrorsUseStableProblems() throws Exception {
        HttpResponse<String> unauthenticatedMediaType = put(
                "/api/v1/resource-monitors/resource-1",
                null,
                MediaType.TEXT_PLAIN_VALUE,
                "{}");
        HttpResponse<String> unauthenticatedAccept = get(
                "/api/v1/resource-monitors/resource-1",
                null,
                MediaType.APPLICATION_XML_VALUE);
        HttpResponse<String> methodNotAllowed = post("/api/v1/system/status", API_TOKEN);
        HttpResponse<String> unsupportedMediaType = put(
                "/api/v1/resource-monitors/resource-1",
                API_TOKEN,
                MediaType.TEXT_PLAIN_VALUE,
                "{}");
        HttpResponse<String> oversizedUnsupportedMediaType = put(
                "/api/v1/resource-monitors/resource-1",
                API_TOKEN,
                "application/vnd.baton-watch+json",
                "x".repeat(MonitorApiRequestBodyLimitFilter.MAX_REQUEST_BODY_BYTES + 1));
        HttpResponse<String> notAcceptable = get(
                "/api/v1/resource-monitors/resource-1",
                API_TOKEN,
                MediaType.APPLICATION_XML_VALUE);

        assertUnauthorized(unauthenticatedMediaType);
        assertUnauthorized(unauthenticatedAccept);
        assertProblem(
                methodNotAllowed,
                405,
                "urn:baton-watch:problem:method-not-allowed",
                "지원하지 않는 HTTP 메서드입니다",
                "METHOD_NOT_ALLOWED");
        assertHeaderContains(methodNotAllowed, HttpHeaders.ALLOW, "GET");
        assertProblem(
                unsupportedMediaType,
                415,
                "urn:baton-watch:problem:unsupported-media-type",
                "지원하지 않는 요청 본문 형식입니다",
                "UNSUPPORTED_MEDIA_TYPE");
        assertHeaderContains(
                unsupportedMediaType,
                HttpHeaders.ACCEPT,
                MediaType.APPLICATION_JSON_VALUE);
        assertProblem(
                oversizedUnsupportedMediaType,
                415,
                "urn:baton-watch:problem:unsupported-media-type",
                "지원하지 않는 요청 본문 형식입니다",
                "UNSUPPORTED_MEDIA_TYPE");
        assertProblem(
                notAcceptable,
                406,
                "urn:baton-watch:problem:not-acceptable",
                "요청한 응답 형식을 지원하지 않습니다",
                "NOT_ACCEPTABLE");
        assertHeaderContains(
                notAcceptable,
                HttpHeaders.ACCEPT,
                MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void checkRequestsRequireAuthenticationAndDoNotRequireCsrf() throws Exception {
        String path = "/api/v1/resource-monitors/resource-1/check-requests";
        assertUnauthorized(post(path, null));
        assertUnauthorized(post(path, "wrong-token"));
        HttpResponse<String> response = post(path, API_TOKEN);
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(objectMapper.readTree(response.body()).required("status").stringValue())
                .isEqualTo("SCHEDULED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "BATCH", "PUT", "POST"})
    void temporaryStorageFailuresPreserveAuthenticationAndExposeOnlyRetryGuidance(String operation) throws Exception {
        assertUnauthorized(unavailableRequest(operation, null));
        assertUnauthorized(unavailableRequest(operation, "wrong-token"));
        HttpResponse<String> response = unavailableRequest(operation, API_TOKEN);

        assertProblem(response, 503, "urn:baton-watch:problem:service-unavailable",
                "일시적으로 요청을 처리할 수 없습니다", "SERVICE_UNAVAILABLE");
        assertThat(response.headers().firstValue(HttpHeaders.RETRY_AFTER)).contains("5");
        assertHeaderContains(response, HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(response.body()).doesNotContain("raw-storage-secret", "raw-sql-secret");
    }

    private HttpResponse<String> unavailableRequest(String operation, String token) throws Exception {
        String path = "/api/v1/resource-monitors/storage-unavailable";
        return switch (operation) {
            case "GET" -> get(path, token);
            case "BATCH" -> get("/api/v1/resource-monitors?resourceReference=storage-unavailable", token);
            case "PUT" -> put(path, token, "{\"sourceRevision\":42,\"monitoringState\":\"INACTIVE\"}");
            case "POST" -> post(path + "/check-requests", token);
            default -> throw new IllegalArgumentException("지원하지 않는 테스트 요청입니다");
        };
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return get(path, token, MediaType.APPLICATION_JSON_VALUE);
    }

    private HttpResponse<String> get(String path, String token, String accept) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET(), token, accept);
    }

    private HttpResponse<String> getWithAuthorization(String path, String authorization) throws Exception {
        return send(
                HttpRequest.newBuilder(uri(path))
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .GET(),
                null);
    }

    private HttpResponse<String> put(String path, String token, String body) throws Exception {
        return put(path, token, MediaType.APPLICATION_JSON_VALUE, body);
    }

    private HttpResponse<String> put(String path, String token, String contentType, String body) throws Exception {
        return put(path, token, contentType, HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> put(
            String path,
            String token,
            String contentType,
            HttpRequest.BodyPublisher body) throws Exception {
        return send(
                HttpRequest.newBuilder(uri(path))
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .PUT(body),
                token);
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()), token);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String token) throws Exception {
        return send(request, token, MediaType.APPLICATION_JSON_VALUE);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String token, String accept) throws Exception {
        request.header(HttpHeaders.ACCEPT, accept);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + serverPort + CONTEXT_PATH + path);
    }

    private static HttpRequest.BodyPublisher chunked(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes));
    }

    private void assertUnauthorized(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(401);
        MediaType contentType = MediaType.parseMediaType(
                response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow());
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(response.headers().firstValue(HttpHeaders.LOCATION)).isEmpty();
        assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE))
                .contains("Bearer");

        JsonNode problem = objectMapper.readTree(response.body());
        assertThat(problem.size()).isEqualTo(4);
        assertThat(problem.required("type").stringValue())
                .isEqualTo("urn:baton-watch:problem:unauthorized");
        assertThat(problem.required("title").stringValue()).isEqualTo("유효한 인증 토큰이 필요합니다");
        assertThat(problem.required("status").intValue()).isEqualTo(401);
        assertThat(problem.required("code").stringValue()).isEqualTo("UNAUTHORIZED");
    }

    private void assertProblem(
            HttpResponse<String> response,
            int status,
            String type,
            String title,
            String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        MediaType contentType = MediaType.parseMediaType(
                response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow());
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(response.headers().firstValue(HttpHeaders.LOCATION)).isEmpty();
        assertThat(response.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();

        JsonNode problem = objectMapper.readTree(response.body());
        assertThat(problem.size()).isEqualTo(5);
        assertThat(problem.required("type").stringValue()).isEqualTo(type);
        assertThat(problem.required("title").stringValue()).isEqualTo(title);
        assertThat(problem.required("status").intValue()).isEqualTo(status);
        assertThat(problem.required("instance").stringValue()).isEqualTo("urn:baton-watch:request");
        assertThat(problem.required("code").stringValue()).isEqualTo(code);
    }

    private void assertHeaderContains(HttpResponse<String> response, String name, String expected) {
        assertThat(response.headers().firstValue(name))
                .hasValueSatisfying(value -> assertThat(value).contains(expected));
    }

    private static MonitorProjection projection() {
        return new MonitorProjection(
                new ResourceReference("resource-1"),
                new SourceRevision(42),
                MonitoringState.INACTIVE,
                new HealthDerivation(Health.UNKNOWN, 0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = DataSourceAutoConfiguration.class,
            excludeName = "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
    @Import({
        MonitorApiSecurityConfiguration.class,
        MonitorApiExceptionHandler.class,
        ResourceMonitorController.class,
        SystemStatusController.class,
        TestWebConfiguration.class
    })
    static class TestApplication {
    }

    @Configuration(proxyBeanMethods = false)
    static class TestWebConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        WatchProperties watchProperties() {
            return BootstrapTestFixtures.watchProperties(API_TOKEN);
        }

        @Bean
        SynchronizeMonitorUseCase synchronizeMonitorUseCase() {
            return command -> {
                if (command.resourceReference().value().equals("storage-unavailable")) {
                    throw new CannotCreateTransactionException(
                            "raw-storage-secret", new SQLException("raw-sql-secret"));
                }
                return new SynchronizationResult(SynchronizationStatus.APPLIED, projection());
            };
        }

        @Bean
        RequestMonitorCheckUseCase requestMonitorCheckUseCase() {
            return reference -> {
                if (reference.value().equals("storage-unavailable")) {
                    throw new QueryTimeoutException("raw-storage-secret");
                }
                return new MonitorCheckRequestResult(MonitorCheckRequestResult.Status.SCHEDULED, NOW, 0);
            };
        }

        @Bean
        GetMonitorProjectionUseCase getMonitorProjectionUseCase() {
            return reference -> {
                if (reference.value().equals("storage-unavailable")) {
                    throw new CannotGetJdbcConnectionException("raw-storage-secret", new SQLException("raw-sql-secret"));
                }
                return Optional.of(projection());
            };
        }

        @Bean
        GetMonitorProjectionsUseCase getMonitorProjectionsUseCase() {
            return references -> {
                if (references.contains(new ResourceReference("storage-unavailable"))) {
                    throw new DataAccessResourceFailureException("raw-storage-secret");
                }
                return references.contains(projection().resourceReference()) ? List.of(projection()) : List.of();
            };
        }

        @Bean
        GetSystemStatusUseCase getSystemStatusUseCase() {
            return () -> new SystemStatus("baton-watch", SystemStatus.State.UP, NOW);
        }
    }
}

package com.personal.baton.watch.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.watch.adapter.in.web.monitoring.MonitorApiExceptionHandler;
import com.personal.baton.watch.adapter.in.web.monitoring.ResourceMonitorController;
import com.personal.baton.watch.adapter.in.web.system.SystemStatusController;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.model.SynchronizationStatus;
import com.personal.baton.watch.application.monitoring.port.in.GetMonitorProjectionUseCase;
import com.personal.baton.watch.application.monitoring.port.in.SynchronizeMonitorUseCase;
import com.personal.baton.watch.application.system.port.in.GetSystemStatusUseCase;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.MonitoringState;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.system.SystemStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = MonitorApiSecurityIntegrationTest.TestApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "server.servlet.context-path=/watch",
            "management.server.port=-1"
        })
class MonitorApiSecurityIntegrationTest {

    private static final String API_TOKEN = "monitor-api-token-0123456789-abcdef";
    private static final String CONTEXT_PATH = "/watch";
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int serverPort;

    private final ObjectMapper objectMapper;

    @Autowired
    MonitorApiSecurityIntegrationTest(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
                "Invalid request",
                "INVALID_REQUEST");
        assertThat(valid.statusCode()).isEqualTo(200);
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
                "Route not found",
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
                "Request rejected",
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
                "Method not allowed",
                "METHOD_NOT_ALLOWED");
        assertHeaderContains(methodNotAllowed, HttpHeaders.ALLOW, "GET");
        assertProblem(
                unsupportedMediaType,
                415,
                "urn:baton-watch:problem:unsupported-media-type",
                "Unsupported media type",
                "UNSUPPORTED_MEDIA_TYPE");
        assertHeaderContains(
                unsupportedMediaType,
                HttpHeaders.ACCEPT,
                MediaType.APPLICATION_JSON_VALUE);
        assertProblem(
                notAcceptable,
                406,
                "urn:baton-watch:problem:not-acceptable",
                "Not acceptable",
                "NOT_ACCEPTABLE");
        assertHeaderContains(
                notAcceptable,
                HttpHeaders.ACCEPT,
                MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void successfulAuthenticationIsNotReusedAsASession() throws Exception {
        HttpResponse<String> authenticated = get("/api/v1/resource-monitors/resource-1", API_TOKEN);
        HttpResponse<String> followingRequest = get("/api/v1/resource-monitors/resource-1", null);

        assertThat(authenticated.statusCode()).isEqualTo(200);
        assertThat(authenticated.headers().firstValue(HttpHeaders.SET_COOKIE)).isEmpty();
        assertUnauthorized(followingRequest);
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
        return send(
                HttpRequest.newBuilder(uri(path))
                        .header(HttpHeaders.CONTENT_TYPE, contentType)
                        .PUT(HttpRequest.BodyPublishers.ofString(body)),
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
        assertThat(problem.required("title").stringValue()).isEqualTo("Unauthorized");
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
                Health.UNKNOWN,
                0,
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
        WatchProperties watchProperties() {
            return new WatchProperties(
                    API_TOKEN,
                    Duration.ofSeconds(1),
                    Duration.ofMinutes(1),
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(1),
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(10),
                    Duration.ofDays(30),
                    1,
                    100,
                    new WatchProperties.Http(
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(3),
                            Duration.ofSeconds(5),
                            64 * 1024,
                            3,
                            100,
                            8 * 1024,
                            2,
                            8,
                            1,
                            1));
        }

        @Bean
        SynchronizeMonitorUseCase synchronizeMonitorUseCase() {
            return command -> new SynchronizationResult(SynchronizationStatus.APPLIED, projection());
        }

        @Bean
        GetMonitorProjectionUseCase getMonitorProjectionUseCase() {
            return resourceReference -> Optional.of(projection());
        }

        @Bean
        GetSystemStatusUseCase getSystemStatusUseCase() {
            return () -> new SystemStatus("baton-watch", SystemStatus.State.UP, NOW);
        }
    }
}

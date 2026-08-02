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
        assertThat(malformed.statusCode()).isEqualTo(400);
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
        assertThat(authenticated.statusCode()).isEqualTo(404);
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
        return send(HttpRequest.newBuilder(uri(path)).GET(), token);
    }

    private HttpResponse<String> put(String path, String token, String body) throws Exception {
        return send(
                HttpRequest.newBuilder(uri(path))
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .PUT(HttpRequest.BodyPublishers.ofString(body)),
                token);
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()), token);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String token) throws Exception {
        request.header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
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

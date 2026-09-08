package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

class JdbcResultIntegrityIntegrationTest extends MonitoringPersistenceIntegrationTestSupport {

    private static final String REFERENCE = "resource:result-integrity";

    @BeforeEach
    void storeSuccessfulCheck() {
        synchronize(REFERENCE, 1, "https://example.com/", BASE_TIME);
        var claim = claimOne();
        checkWorkPersistence.finalizeCheck(finalization(
                claim,
                CheckObservation.forHttpStatus(200, Duration.ZERO, 0, 0),
                claim.claimedAt(),
                claim.claimedAt().plus(INTERVAL)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "HTTP_CLIENT_ERROR", "HTTP_SERVER_ERROR"})
    void rejectsHttpResultsWithoutAStatusCode(String outcome) {
        assertThatThrownBy(() -> jdbc.update("""
                        UPDATE watch_result
                        SET outcome = ?, http_status_code = NULL
                        """, outcome))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> assertSqlState(failure, "23514"));
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING, HTTP_CLIENT_ERROR,",
            "PENDING, HTTP_SERVER_ERROR,",
            "DELIVERED, DELIVERED,",
            "DELIVERED,,",
            "PENDING,,200",
            "DELIVERED,,204"
    })
    void rejectsIncompleteDeliveryResults(String status, String outcome, Integer httpStatus) {
        assertThatThrownBy(() -> jdbc.update("""
                        UPDATE watch_health_change_event
                        SET delivery_status = ?, delivery_attempt = 1,
                            next_attempt_at = CASE WHEN ? = 'PENDING' THEN changed_at ELSE NULL END,
                            delivered_at = CASE WHEN ? = 'DELIVERED' THEN changed_at ELSE NULL END,
                            last_delivery_outcome = ?, last_http_status_code = ?
                        WHERE resource_reference = ?
                        """, status, status, status, outcome, httpStatus, REFERENCE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> assertSqlState(failure, "23514"));
    }

    @Test
    void migrationPreservesExistingCheckAndDeliveredEvent() {
        prepareVersionFive();
        jdbc.update("""
                UPDATE watch_health_change_event
                SET delivery_status = 'DELIVERED', delivery_attempt = 1,
                    next_attempt_at = NULL, delivered_at = changed_at,
                    last_delivery_outcome = 'DELIVERED', last_http_status_code = 204
                """);
        var results = jdbc.queryForList("SELECT * FROM watch_result");
        var events = jdbc.queryForList("SELECT * FROM watch_health_change_event");

        Flyway.configure().dataSource(testDataSource).load().migrate();

        assertThat(jdbc.queryForList("SELECT * FROM watch_result")).isEqualTo(results);
        assertThat(jdbc.queryForList("SELECT * FROM watch_health_change_event")).isEqualTo(events);
    }

    @Test
    void migrationRejectsIncompleteHistoryWithoutRewritingIt() {
        prepareVersionFive();
        jdbc.update("UPDATE watch_result SET http_status_code = NULL");
        var results = jdbc.queryForList("SELECT * FROM watch_result");

        assertThatThrownBy(() -> Flyway.configure().dataSource(testDataSource).load().migrate())
                .isInstanceOf(FlywayException.class)
                .satisfies(failure -> assertSqlState(failure, "23514"));

        assertThat(jdbc.queryForList("SELECT * FROM watch_result")).isEqualTo(results);
        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("5");
    }

    private void prepareVersionFive() {
        var versionFive = Flyway.configure().dataSource(testDataSource)
                .cleanDisabled(false).target("5").load();
        versionFive.clean();
        versionFive.migrate();
        storeSuccessfulCheck();
    }
}

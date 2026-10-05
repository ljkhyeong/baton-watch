package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
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
        finalizeAt(claim, claim.claimedAt());
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
}

package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcDatabaseClockAdapterIntegrationTest extends PostgresPersistenceIntegrationTestSupport {

    @Test
    void readsThePostgresWallClockAsAnInstant() {
        JdbcDatabaseClockAdapter adapter =
                new JdbcDatabaseClockAdapter(JdbcClient.create(testDataSource));
        Instant before = databaseClock();

        Instant current = adapter.currentTime();

        assertThat(current).isBetween(before, databaseClock());
    }

    private Instant databaseClock() {
        return jdbc.queryForObject(
                        "SELECT clock_timestamp()",
                        OffsetDateTime.class)
                .toInstant();
    }
}

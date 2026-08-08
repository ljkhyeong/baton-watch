package com.personal.baton.watch.adapter.out.persistence.monitoring;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ExtendWith(SharedPostgresExtension.class)
@Testcontainers(disabledWithoutDocker = false)
@ResourceLock("baton-watch-postgres")
abstract class PostgresPersistenceIntegrationTestSupport {

    protected static final PostgreSQLContainer POSTGRES = SharedPostgresExtension.POSTGRES;
    protected static final Instant BASE_TIME = Instant.parse("2026-08-01T00:00:00Z");

    protected JdbcTemplate jdbc;
    protected DataSource testDataSource;

    @BeforeEach
    void migrateFreshDatabase() {
        testDataSource = dataSource();
        Flyway flyway = Flyway.configure()
                .dataSource(testDataSource)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(testDataSource);
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    protected static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}

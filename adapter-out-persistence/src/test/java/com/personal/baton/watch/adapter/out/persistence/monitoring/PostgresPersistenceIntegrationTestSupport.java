package com.personal.baton.watch.adapter.out.persistence.monitoring;

import java.time.Instant;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ExtendWith(SharedPostgresExtension.class)
@ResourceLock("baton-watch-postgres")
abstract class PostgresPersistenceIntegrationTestSupport {

    protected static final PostgreSQLContainer POSTGRES = SharedPostgresExtension.POSTGRES;
    protected static final Instant BASE_TIME = Instant.parse("2026-08-01T00:00:00Z");

    protected JdbcTemplate jdbc;
    protected DataSource testDataSource;

    @BeforeEach
    void migrateFreshDatabase() {
        testDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(testDataSource)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(testDataSource);
    }

}

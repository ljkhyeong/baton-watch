package com.personal.baton.watch.adapter.out.persistence.monitoring;

import com.personal.baton.watch.application.monitoring.port.out.DatabaseClockPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL의 실제 현재 시각을 읽는 JDBC 어댑터다. */
public final class JdbcDatabaseClockAdapter implements DatabaseClockPort {

    private final JdbcClient jdbc;

    public JdbcDatabaseClockAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Instant currentTime() {
        return jdbc.sql("SELECT clock_timestamp()")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }
}

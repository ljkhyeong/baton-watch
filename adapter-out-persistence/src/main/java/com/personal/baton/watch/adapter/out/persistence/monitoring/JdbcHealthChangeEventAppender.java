package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static com.personal.baton.watch.adapter.out.persistence.monitoring.MonitoringJdbcRows.databaseTime;

import com.personal.baton.watch.domain.monitoring.Health;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 호출자의 활성 트랜잭션 안에서 이벤트를 추가하며 자체 트랜잭션은 시작하지 않는다. */
final class JdbcHealthChangeEventAppender {

    private final JdbcClient jdbc;

    JdbcHealthChangeEventAppender(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void append(
            String resourceReference,
            long sourceRevision,
            UUID attemptId,
            Health previousHealth,
            Health currentHealth,
            Instant changedAt) {
        jdbc.sql("""
                        INSERT INTO watch_health_change_event (
                            event_id,
                            resource_reference,
                            source_revision,
                            attempt_id,
                            previous_health,
                            current_health,
                            changed_at,
                            delivery_status,
                            delivery_attempt,
                            next_attempt_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        resourceReference,
                        sourceRevision,
                        attemptId,
                        previousHealth.name(),
                        currentHealth.name(),
                        databaseTime(changedAt),
                        databaseTime(changedAt))
                .update();
    }
}

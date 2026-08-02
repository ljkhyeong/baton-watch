package com.personal.baton.watch.adapter.out.persistence.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.baton.watch.application.monitoring.model.CheckFinalization;
import com.personal.baton.watch.application.monitoring.model.CheckFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.application.monitoring.model.ClaimedCheck;
import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalization;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryFinalizationStatus;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.application.monitoring.model.SynchronizationResult;
import com.personal.baton.watch.application.monitoring.model.SynchronizationStatus;
import com.personal.baton.watch.application.monitoring.model.SynchronizeMonitorCommand;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class JdbcMonitoringPersistenceAdapterIntegrationTest {

    private static final String POSTGRES_IMAGE = "postgres:18.4-alpine";
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration INTERVAL = Duration.ofSeconds(60);
    private static final Instant BASE_TIME = Instant.parse("2026-08-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("baton_watch")
            .withUsername("baton_watch")
            .withPassword("integration-test");

    private JdbcMonitoringPersistenceAdapter adapter;
    private JdbcHealthChangeEventDeliveryAdapter deliveryAdapter;
    private JdbcTemplate jdbc;
    private DataSource testDataSource;

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
        adapter = new JdbcMonitoringPersistenceAdapter(jdbc, new DataSourceTransactionManager(testDataSource));
        deliveryAdapter = new JdbcHealthChangeEventDeliveryAdapter(
                jdbc, new DataSourceTransactionManager(testDataSource));
    }

    @Test
    void migrationCreatesFourMetadataOnlyTablesWithDomainBounds() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name LIKE 'watch_%'
                ORDER BY table_name
                """, String.class);

        assertThat(tables).containsExactly(
                "watch_attempt", "watch_health_change_event", "watch_monitor", "watch_result");
        assertThat(characterMaximum("watch_monitor", "resource_reference")).isEqualTo(128);
        assertThat(characterMaximum("watch_monitor", "target_url")).isEqualTo(2048);
        assertThat(characterMaximum("watch_attempt", "resource_reference")).isEqualTo(128);
        assertThat(characterMaximum("watch_attempt", "target_url")).isEqualTo(2048);

        List<String> columns = jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name LIKE 'watch_%'
                """, String.class);
        assertThat(columns)
                .noneMatch(name -> name.contains("body"))
                .noneMatch(name -> name.contains("resolved"))
                .noneMatch(name -> name.contains("exception"))
                .noneMatch(name -> name.contains("header"))
                .noneMatch(name -> name.contains("cookie"));
    }

    @Test
    void springBootFlywayAutoConfigurationAppliesThePackagedMigration() {
        Flyway.configure().dataSource(testDataSource).cleanDisabled(false).load().clean();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Flyway.class);
                    assertThat(context.getBean(Flyway.class).info().current().getVersion().getVersion())
                            .isEqualTo("2");
                    JdbcTemplate bootJdbc = new JdbcTemplate(context.getBean(DataSource.class));
                    assertThat(bootJdbc.queryForObject(
                            "SELECT COUNT(*) FROM watch_monitor", Integer.class)).isZero();
                });
    }

    @Test
    void synchronizeEnforcesMonotonicRevisionAndEqualRevisionPayloadRules() {
        SynchronizationResult created = synchronize("resource:revision", 5, "https://one.example/path", BASE_TIME);

        assertThat(created.status()).isEqualTo(SynchronizationStatus.APPLIED);
        assertThat(created.projection().health()).isEqualTo(Health.UNKNOWN);
        assertThat(created.projection().nextCheckAt()).contains(BASE_TIME);

        SynchronizationResult stale = synchronize(
                "resource:revision", 4, "https://stale.example/path", BASE_TIME.plusSeconds(1));
        SynchronizationResult unchanged = synchronize(
                "resource:revision", 5, "https://one.example/path", BASE_TIME.plusSeconds(2));
        SynchronizationResult conflict = synchronize(
                "resource:revision", 5, "https://conflict.example/path", BASE_TIME.plusSeconds(3));

        assertThat(stale.status()).isEqualTo(SynchronizationStatus.STALE_REVISION);
        assertThat(unchanged.status()).isEqualTo(SynchronizationStatus.UNCHANGED);
        assertThat(conflict.status()).isEqualTo(SynchronizationStatus.REVISION_CONFLICT);
        assertThat(jdbc.queryForObject(
                "SELECT source_revision FROM watch_monitor WHERE resource_reference = ?",
                Long.class,
                "resource:revision")).isEqualTo(5L);
        assertThat(jdbc.queryForObject(
                "SELECT target_url FROM watch_monitor WHERE resource_reference = ?",
                String.class,
                "resource:revision")).isEqualTo("https://one.example/path");
        assertThat(count("watch_health_change_event")).isZero();
    }

    @Test
    void claimsHistoricalUnsafeTargetWithoutRollingBackOtherDueWork() {
        String historicalTarget = "https://legacy.example/%0d%0aHost:internal";
        synchronize("resource:a-legacy", 1, "https://legacy.example/path", BASE_TIME);
        synchronize("resource:b-current", 1, "https://current.example/path", BASE_TIME);
        jdbc.update(
                "UPDATE watch_monitor SET target_url = ? WHERE resource_reference = ?",
                historicalTarget,
                "resource:a-legacy");

        List<ClaimedCheck> claims = adapter.claimDueChecks(BASE_TIME, BASE_TIME.plus(LEASE), 2);

        assertThat(claims)
                .extracting(claim -> claim.resourceReference().value())
                .containsExactly("resource:a-legacy", "resource:b-current");
        assertThat(claims)
                .extracting(claim -> claim.targetUrl().value())
                .containsExactly(historicalTarget, "https://current.example/path");
        assertThat(count("watch_attempt")).isEqualTo(2);
    }

    @Test
    void concurrentInitialSynchronizationReturnsAppliedThenIdempotentInsteadOfPrimaryKeyFailure() throws Exception {
        SynchronizeMonitorCommand command = SynchronizeMonitorCommand.active(
                new ResourceReference("resource:concurrent-sync"),
                new SourceRevision(1),
                new TargetUrl("https://concurrent.example/path"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SynchronizationStatus> first = executor.submit(() -> synchronizeConcurrently(command, ready, start));
            Future<SynchronizationStatus> second = executor.submit(() -> synchronizeConcurrently(command, ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(SynchronizationStatus.APPLIED, SynchronizationStatus.UNCHANGED);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM watch_monitor
                    WHERE resource_reference = 'resource:concurrent-sync'
                    """, Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void targetChangeResetsProjectionDueNowInvalidatesLeaseAndRecordsHealthChange() {
        synchronize("resource:target-change", 1, "https://one.example/path", BASE_TIME);
        ClaimedCheck first = claimOne(BASE_TIME);
        Instant completedAt = BASE_TIME.plusSeconds(1);
        assertThat(adapter.finalizeCheck(finalization(
                        first, CheckObservation.forHttpStatus(204), completedAt, completedAt.plus(INTERVAL)))
                .status()).isEqualTo(CheckFinalizationStatus.APPLIED);

        ClaimedCheck inFlight = claimOne(completedAt.plus(INTERVAL));
        Instant changedAt = completedAt.plus(INTERVAL).plusSeconds(1);
        SynchronizationResult changed = synchronize(
                "resource:target-change", 2, "https://two.example/path", changedAt);

        assertThat(changed.projection().health()).isEqualTo(Health.UNKNOWN);
        assertThat(changed.projection().lastOutcome()).isEmpty();
        assertThat(changed.projection().lastCheckedAt()).isEmpty();
        assertThat(changed.projection().nextCheckAt()).contains(changedAt);
        assertThat(adapter.finalizeCheck(finalization(
                        inFlight,
                        CheckObservation.forHttpStatus(200),
                        changedAt.plusSeconds(1),
                        changedAt.plus(INTERVAL)))
                .status()).isEqualTo(CheckFinalizationStatus.STALE_CLAIM);
        assertThat(count("watch_result")).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT previous_health || '->' || current_health
                FROM watch_health_change_event
                WHERE resource_reference = ?
                ORDER BY changed_at
                """, String.class, "resource:target-change"))
                .containsExactly("UNKNOWN->HEALTHY", "HEALTHY->UNKNOWN");
    }

    @Test
    void expiredLeaseCanBeRecoveredAndTheOlderAttemptBecomesStale() {
        synchronize("resource:lease", 1, "https://lease.example/path", BASE_TIME);
        ClaimedCheck first = claimOne(BASE_TIME);

        assertThat(adapter.claimDueChecks(BASE_TIME.plusSeconds(29), BASE_TIME.plusSeconds(59), 1)).isEmpty();
        ClaimedCheck recovered = claimOne(BASE_TIME.plusSeconds(30));

        assertThat(recovered.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(adapter.finalizeCheck(finalization(
                        first,
                        CheckObservation.forHttpStatus(200),
                        BASE_TIME.plusSeconds(31),
                        BASE_TIME.plusSeconds(91)))
                .status()).isEqualTo(CheckFinalizationStatus.STALE_CLAIM);
        assertThat(adapter.finalizeCheck(finalization(
                        recovered,
                        CheckObservation.forHttpStatus(200),
                        BASE_TIME.plusSeconds(32),
                        BASE_TIME.plusSeconds(92)))
                .status()).isEqualTo(CheckFinalizationStatus.APPLIED);
        assertThat(count("watch_attempt")).isEqualTo(2);
        assertThat(count("watch_result")).isEqualTo(1);
    }

    @Test
    void duplicateAndWrongTokenFinalizationCannotDuplicateResultOrEvent() {
        synchronize("resource:idempotent", 1, "https://idempotent.example/path", BASE_TIME);
        ClaimedCheck claimed = claimOne(BASE_TIME);
        CheckFinalization valid = finalization(
                claimed,
                CheckObservation.forHttpStatus(200, Duration.ofMillis(17), 42, 1),
                BASE_TIME.plusSeconds(1),
                BASE_TIME.plusSeconds(61));
        CheckFinalization wrongToken = new CheckFinalization(
                claimed.attemptId(),
                UUID.randomUUID(),
                claimed.resourceReference(),
                claimed.sourceRevision(),
                valid.observation(),
                valid.completedAt(),
                valid.nextCheckAt());

        assertThat(adapter.finalizeCheck(wrongToken).status()).isEqualTo(CheckFinalizationStatus.STALE_CLAIM);
        assertThat(adapter.finalizeCheck(valid).status()).isEqualTo(CheckFinalizationStatus.APPLIED);
        assertThat(adapter.finalizeCheck(valid).status()).isEqualTo(CheckFinalizationStatus.ALREADY_FINALIZED);

        assertThat(count("watch_result")).isEqualTo(1);
        assertThat(count("watch_health_change_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT response_bytes FROM watch_result WHERE attempt_id = ?",
                Long.class,
                claimed.attemptId())).isEqualTo(42L);
    }

    @Test
    void resultProjectionAndHealthEventRollBackTogetherWhenEventInsertFails() {
        synchronize("resource:atomic", 1, "https://atomic.example/path", BASE_TIME);
        ClaimedCheck claimed = claimOne(BASE_TIME);
        jdbc.update("""
                INSERT INTO watch_health_change_event (
                    event_id, resource_reference, source_revision, attempt_id,
                    previous_health, current_health, changed_at, next_attempt_at
                ) VALUES (?, ?, ?, ?, 'UNKNOWN', 'HEALTHY', ?, ?)
                """,
                UUID.randomUUID(),
                claimed.resourceReference().value(),
                claimed.sourceRevision().value(),
                claimed.attemptId(),
                OffsetDateTime.ofInstant(BASE_TIME.minusSeconds(1), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE_TIME.minusSeconds(1), ZoneOffset.UTC));

        CheckFinalization finalization = finalization(
                claimed,
                CheckObservation.forHttpStatus(200),
                BASE_TIME.plusSeconds(1),
                BASE_TIME.plusSeconds(61));
        assertThatThrownBy(() -> adapter.finalizeCheck(finalization))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("watch_result")).isZero();
        MonitorProjection projection = projection("resource:atomic");
        assertThat(projection.health()).isEqualTo(Health.UNKNOWN);
        assertThat(projection.lastOutcome()).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT lease_attempt_id = ?
                FROM watch_monitor
                WHERE resource_reference = ?
                """, Boolean.class, claimed.attemptId(), claimed.resourceReference().value())).isTrue();
    }

    @Test
    void staleSweepTransitionsAtTheCutoffOnceAndPreservesFailureDerivation() {
        synchronize("resource:stale", 1, "https://stale.example/path", BASE_TIME);
        ClaimedCheck claimed = claimOne(BASE_TIME);
        Instant completedAt = BASE_TIME.plusSeconds(1);
        adapter.finalizeCheck(finalization(
                claimed,
                CheckObservation.failure(CheckOutcome.CONNECT_TIMEOUT),
                completedAt,
                completedAt.plus(INTERVAL)));

        assertThat(adapter.markStaleUnknown(
                completedAt.minusNanos(1_000), completedAt.plusSeconds(600), 10)).isZero();
        assertThat(adapter.markStaleUnknown(
                completedAt, completedAt.plusSeconds(600), 10)).isEqualTo(1);
        assertThat(adapter.markStaleUnknown(
                completedAt, completedAt.plusSeconds(601), 10)).isZero();

        MonitorProjection projection = projection("resource:stale");
        assertThat(projection.health()).isEqualTo(Health.UNKNOWN);
        assertThat(projection.consecutiveFailures()).isEqualTo(1);
        assertThat(count("watch_health_change_event")).isEqualTo(2);
    }

    @Test
    void retentionIsBoundedStrictlyBeforeCutoffAndKeepsOutboxAttemptFacts() {
        Instant cutoff = BASE_TIME.plus(Duration.ofDays(30));
        List<ClaimedCheck> attempts = List.of(
                claimed("resource:abandoned", BASE_TIME),
                claimed("resource:before", BASE_TIME.plusSeconds(1)),
                claimed("resource:at", BASE_TIME.plusSeconds(2)),
                claimed("resource:after", BASE_TIME.plusSeconds(3)));
        adapter.synchronize(
                SynchronizeMonitorCommand.inactive(
                        attempts.getFirst().resourceReference(), new SourceRevision(2)),
                BASE_TIME.plusSeconds(31));

        finalizeAt(attempts.get(1), cutoff.minusSeconds(1));
        finalizeAt(attempts.get(2), cutoff);
        finalizeAt(attempts.get(3), cutoff.plusSeconds(1));

        UUID retainedOutboxAttempt = attempts.get(1).attemptId();
        assertThat(adapter.purgeAttempts(cutoff, 1)).isEqualTo(1);
        assertThat(adapter.purgeAttempts(cutoff, 1)).isEqualTo(1);
        assertThat(adapter.purgeAttempts(cutoff, 1)).isZero();

        assertThat(count("watch_attempt")).isEqualTo(2);
        assertThat(count("watch_result")).isEqualTo(2);
        assertThat(count("watch_health_change_event")).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM watch_health_change_event
                WHERE attempt_id = ?
                """, Integer.class, retainedOutboxAttempt)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT attempt_id FROM watch_attempt ORDER BY claimed_at", UUID.class))
                .containsExactly(attempts.get(2).attemptId(), attempts.get(3).attemptId());
    }

    @Test
    void v2MigrationMakesExistingOutboxEventsPendingAndImmediatelyDue() {
        Flyway versionOne = Flyway.configure()
                .dataSource(testDataSource)
                .cleanDisabled(false)
                .target("1")
                .load();
        versionOne.clean();
        versionOne.migrate();

        jdbc.update("""
                INSERT INTO watch_monitor (
                    resource_reference, source_revision, monitor_status, target_url,
                    current_health, consecutive_failures, next_check_at, created_at, updated_at
                ) VALUES (?, 1, 'INACTIVE', NULL, 'UNKNOWN', 0, NULL, ?, ?)
                """,
                "resource:migration",
                databaseTime(BASE_TIME),
                databaseTime(BASE_TIME));
        UUID eventId = UUID.randomUUID();
        Instant changedAt = BASE_TIME.plusSeconds(1);
        jdbc.update("""
                INSERT INTO watch_health_change_event (
                    event_id, resource_reference, source_revision, attempt_id,
                    previous_health, current_health, changed_at
                ) VALUES (?, ?, 1, NULL, 'HEALTHY', 'UNKNOWN', ?)
                """,
                eventId,
                "resource:migration",
                databaseTime(changedAt));

        Flyway.configure().dataSource(testDataSource).load().migrate();

        assertThat(jdbc.queryForMap(
                        "SELECT delivery_status, delivery_attempt, next_attempt_at FROM watch_health_change_event WHERE event_id = ?",
                        eventId))
                .containsEntry("delivery_status", "PENDING")
                .containsEntry("delivery_attempt", 0);
        assertThat(jdbc.queryForObject(
                        "SELECT next_attempt_at FROM watch_health_change_event WHERE event_id = ?",
                        OffsetDateTime.class,
                        eventId)
                .toInstant()).isEqualTo(changedAt);
    }

    @Test
    void deliveryLeaseRecoversAtExpiryAndFinalizationIsTokenSafeAndIdempotent() {
        UUID eventId = createDeliveryEvent("resource:delivery-lease", BASE_TIME);
        Instant dueAt = BASE_TIME.plusSeconds(1);

        ClaimedHealthChangeEvent first = claimOneDelivery(dueAt);

        assertThat(first.payload().eventId()).isEqualTo(eventId);
        assertThat(first.deliveryAttempt()).isEqualTo(1);
        assertThat(first.payload().attemptId()).isPresent();
        assertThat(deliveryAdapter.getBacklogSnapshot().pendingCount()).isEqualTo(1);
        assertThat(deliveryAdapter.claimPendingEvents(dueAt.plusSeconds(29), dueAt.plusSeconds(59), 1))
                .isEmpty();

        ClaimedHealthChangeEvent recovered = claimOneDelivery(dueAt.plusSeconds(30));
        assertThat(recovered.payload()).isEqualTo(first.payload());
        assertThat(recovered.deliveryAttempt()).isEqualTo(2);
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());

        assertThat(deliveryAdapter.finalizeDelivery(deliveredFinalization(first, dueAt.plusSeconds(31)))
                        .status())
                .isEqualTo(EventDeliveryFinalizationStatus.STALE_CLAIM);
        EventDeliveryFinalization delivered = deliveredFinalization(recovered, dueAt.plusSeconds(32));
        assertThat(deliveryAdapter.finalizeDelivery(delivered).status())
                .isEqualTo(EventDeliveryFinalizationStatus.APPLIED);
        assertThat(deliveryAdapter.finalizeDelivery(delivered).status())
                .isEqualTo(EventDeliveryFinalizationStatus.ALREADY_DELIVERED);

        assertThat(deliveryAdapter.getBacklogSnapshot().pendingCount()).isZero();
        assertThat(deliveryAdapter.claimPendingEvents(dueAt.plusSeconds(60), dueAt.plusSeconds(90), 1))
                .isEmpty();
        assertThat(jdbc.queryForMap("""
                        SELECT delivery_status, delivery_attempt, last_delivery_outcome, last_http_status_code
                        FROM watch_health_change_event
                        WHERE event_id = ?
                        """, eventId))
                .containsEntry("delivery_status", "DELIVERED")
                .containsEntry("delivery_attempt", 2)
                .containsEntry("last_delivery_outcome", "DELIVERED")
                .containsEntry("last_http_status_code", 204);
    }

    @Test
    void failedDeliveryPersistsBoundedOutcomeAndBecomesClaimableAtRetryBoundary() {
        UUID eventId = createDeliveryEvent("resource:delivery-retry", BASE_TIME);
        Instant dueAt = BASE_TIME.plusSeconds(1);
        ClaimedHealthChangeEvent first = claimOneDelivery(dueAt);
        Instant completedAt = dueAt.plusSeconds(1);
        Instant retryAt = completedAt.plusSeconds(30);

        EventDeliveryFinalization failed = new EventDeliveryFinalization(
                first.payload().eventId(),
                first.leaseToken(),
                first.deliveryAttempt(),
                EventDeliveryObservation.failure(EventDeliveryOutcome.DNS_FAILURE),
                completedAt,
                retryAt);
        assertThat(deliveryAdapter.finalizeDelivery(failed).status())
                .isEqualTo(EventDeliveryFinalizationStatus.APPLIED);

        assertThat(deliveryAdapter.claimPendingEvents(retryAt.minusNanos(1_000), retryAt.plusSeconds(30), 1))
                .isEmpty();
        ClaimedHealthChangeEvent retried = claimOneDelivery(retryAt);
        assertThat(retried.payload().eventId()).isEqualTo(eventId);
        assertThat(retried.deliveryAttempt()).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
                        SELECT delivery_status, last_delivery_outcome, last_http_status_code
                        FROM watch_health_change_event
                        WHERE event_id = ?
                        """, eventId))
                .containsEntry("delivery_status", "PENDING")
                .containsEntry("last_delivery_outcome", "DNS_FAILURE")
                .containsEntry("last_http_status_code", null);
    }

    @Test
    void concurrentDeliveryClaimersReceiveDisjointEvents() throws Exception {
        UUID firstEvent = createDeliveryEvent("resource:delivery-concurrent-1", BASE_TIME);
        UUID secondEvent = createDeliveryEvent("resource:delivery-concurrent-2", BASE_TIME.plusSeconds(2));
        Instant claimedAt = BASE_TIME.plusSeconds(10);
        JdbcHealthChangeEventDeliveryAdapter anotherAdapter = new JdbcHealthChangeEventDeliveryAdapter(
                new JdbcTemplate(testDataSource), new DataSourceTransactionManager(testDataSource));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<ClaimedHealthChangeEvent>> first = executor.submit(
                    () -> claimDeliveriesConcurrently(deliveryAdapter, claimedAt, ready, start));
            Future<List<ClaimedHealthChangeEvent>> second = executor.submit(
                    () -> claimDeliveriesConcurrently(anotherAdapter, claimedAt, ready, start));
            ready.await();
            start.countDown();

            assertThat(first.get()).hasSize(1);
            assertThat(second.get()).hasSize(1);
            assertThat(List.of(
                            first.get().getFirst().payload().eventId(),
                            second.get().getFirst().payload().eventId()))
                    .containsExactlyInAnyOrder(firstEvent, secondEvent);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void saturatedDeliveryAttemptDoesNotBlockLaterPendingEvents() {
        adapter.synchronize(
                SynchronizeMonitorCommand.inactive(
                        new ResourceReference("resource:saturated-attempt"), new SourceRevision(1)),
                BASE_TIME);
        UUID saturated = insertPendingEvent(
                "resource:saturated-attempt", BASE_TIME.minusSeconds(2), Integer.MAX_VALUE);
        UUID following = insertPendingEvent(
                "resource:saturated-attempt", BASE_TIME.minusSeconds(1), 3);

        List<ClaimedHealthChangeEvent> claims = deliveryAdapter.claimPendingEvents(
                BASE_TIME, BASE_TIME.plus(LEASE), 2);

        assertThat(claims).extracting(claim -> claim.payload().eventId())
                .containsExactly(saturated, following);
        assertThat(claims).extracting(ClaimedHealthChangeEvent::deliveryAttempt)
                .containsExactly(Integer.MAX_VALUE, 4);
    }

    @Test
    void deliveredEventRetentionIsBoundedAndNeverDeletesPendingEvents() {
        adapter.synchronize(
                SynchronizeMonitorCommand.inactive(
                        new ResourceReference("resource:event-retention"), new SourceRevision(1)),
                BASE_TIME);
        Instant cutoff = BASE_TIME.plus(Duration.ofDays(30));
        UUID oldOne = insertDeliveredEvent("resource:event-retention", BASE_TIME, cutoff.minusSeconds(2));
        UUID oldTwo = insertDeliveredEvent("resource:event-retention", BASE_TIME.plusSeconds(1), cutoff.minusSeconds(1));
        UUID atCutoff = insertDeliveredEvent("resource:event-retention", BASE_TIME.plusSeconds(2), cutoff);
        UUID pending = insertPendingEvent("resource:event-retention", BASE_TIME.minus(Duration.ofDays(90)));

        assertThat(deliveryAdapter.purgeDeliveredEvents(cutoff, 1)).isEqualTo(1);
        assertThat(deliveryAdapter.purgeDeliveredEvents(cutoff, 1)).isEqualTo(1);
        assertThat(deliveryAdapter.purgeDeliveredEvents(cutoff, 1)).isZero();

        assertThat(jdbc.queryForList(
                        "SELECT event_id FROM watch_health_change_event ORDER BY event_id", UUID.class))
                .containsExactlyInAnyOrder(atCutoff, pending)
                .doesNotContain(oldOne, oldTwo);
        assertThat(deliveryAdapter.getBacklogSnapshot().pendingCount()).isEqualTo(1);
        assertThat(deliveryAdapter.getBacklogSnapshot().oldestChangedAt())
                .contains(BASE_TIME.minus(Duration.ofDays(90)));
    }

    private UUID createDeliveryEvent(String reference, Instant claimedAt) {
        synchronize(reference, 1, "https://" + reference.replace(':', '-') + ".example/path", claimedAt);
        ClaimedCheck check = claimOne(claimedAt);
        Instant changedAt = claimedAt.plusSeconds(1);
        assertThat(adapter.finalizeCheck(finalization(
                        check,
                        CheckObservation.forHttpStatus(200),
                        changedAt,
                        changedAt.plus(INTERVAL)))
                .status()).isEqualTo(CheckFinalizationStatus.APPLIED);
        return jdbc.queryForObject("""
                SELECT event_id
                FROM watch_health_change_event
                WHERE resource_reference = ? AND changed_at = ?
                """, UUID.class, reference, databaseTime(changedAt));
    }

    private ClaimedHealthChangeEvent claimOneDelivery(Instant claimedAt) {
        List<ClaimedHealthChangeEvent> claims = deliveryAdapter.claimPendingEvents(
                claimedAt, claimedAt.plus(LEASE), 1);
        assertThat(claims).hasSize(1);
        return claims.getFirst();
    }

    private EventDeliveryFinalization deliveredFinalization(
            ClaimedHealthChangeEvent event, Instant completedAt) {
        return new EventDeliveryFinalization(
                event.payload().eventId(),
                event.leaseToken(),
                event.deliveryAttempt(),
                EventDeliveryObservation.delivered(204),
                completedAt,
                null);
    }

    private List<ClaimedHealthChangeEvent> claimDeliveriesConcurrently(
            JdbcHealthChangeEventDeliveryAdapter claimingAdapter,
            Instant claimedAt,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return claimingAdapter.claimPendingEvents(claimedAt, claimedAt.plus(LEASE), 1);
    }

    private UUID insertDeliveredEvent(String reference, Instant changedAt, Instant deliveredAt) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO watch_health_change_event (
                    event_id, resource_reference, source_revision, attempt_id,
                    previous_health, current_health, changed_at,
                    delivery_status, delivery_attempt, next_attempt_at,
                    delivered_at, last_delivery_outcome, last_http_status_code
                ) VALUES (?, ?, 1, NULL, 'UNKNOWN', 'HEALTHY', ?, 'DELIVERED', 1, NULL, ?, 'DELIVERED', 204)
                """,
                eventId,
                reference,
                databaseTime(changedAt),
                databaseTime(deliveredAt));
        return eventId;
    }

    private UUID insertPendingEvent(String reference, Instant changedAt) {
        return insertPendingEvent(reference, changedAt, 3);
    }

    private UUID insertPendingEvent(String reference, Instant changedAt, int deliveryAttempt) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO watch_health_change_event (
                    event_id, resource_reference, source_revision, attempt_id,
                    previous_health, current_health, changed_at,
                    delivery_status, delivery_attempt, next_attempt_at,
                    delivered_at, last_delivery_outcome, last_http_status_code
                ) VALUES (?, ?, 1, NULL, 'HEALTHY', 'UNKNOWN', ?, 'PENDING', ?, ?, NULL, 'DNS_FAILURE', NULL)
                """,
                eventId,
                reference,
                databaseTime(changedAt),
                deliveryAttempt,
                databaseTime(changedAt));
        return eventId;
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private SynchronizationResult synchronize(String reference, long revision, String target, Instant at) {
        return adapter.synchronize(
                SynchronizeMonitorCommand.active(
                        new ResourceReference(reference), new SourceRevision(revision), new TargetUrl(target)),
                at);
    }

    private SynchronizationStatus synchronizeConcurrently(
            SynchronizeMonitorCommand command, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return adapter.synchronize(command, BASE_TIME).status();
    }

    private ClaimedCheck claimOne(Instant claimedAt) {
        List<ClaimedCheck> claims = adapter.claimDueChecks(claimedAt, claimedAt.plus(LEASE), 1);
        assertThat(claims).hasSize(1);
        return claims.getFirst();
    }

    private ClaimedCheck claimed(String reference, Instant claimedAt) {
        synchronize(reference, 1, "https://" + reference.replace(':', '-') + ".example/path", claimedAt);
        return claimOne(claimedAt);
    }

    private CheckFinalization finalization(
            ClaimedCheck claimed, CheckObservation observation, Instant completedAt, Instant nextCheckAt) {
        return new CheckFinalization(
                claimed.attemptId(),
                claimed.leaseToken(),
                claimed.resourceReference(),
                claimed.sourceRevision(),
                observation,
                completedAt,
                nextCheckAt);
    }

    private void finalizeAt(ClaimedCheck claimed, Instant completedAt) {
        assertThat(adapter.finalizeCheck(finalization(
                        claimed,
                        CheckObservation.forHttpStatus(200),
                        completedAt,
                        completedAt.plus(INTERVAL)))
                .status()).isEqualTo(CheckFinalizationStatus.APPLIED);
    }

    private MonitorProjection projection(String reference) {
        return adapter.findProjection(new ResourceReference(reference)).orElseThrow();
    }

    private int count(String table) {
        if (!List.of("watch_attempt", "watch_result", "watch_health_change_event").contains(table)) {
            throw new IllegalArgumentException("unsupported test table");
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int characterMaximum(String table, String column) {
        return jdbc.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
    }

    private DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}

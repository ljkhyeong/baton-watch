package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.adapter.out.external.delivery.ApacheHealthChangeEventSender;
import com.personal.baton.watch.adapter.out.external.delivery.EventDeliveryLimits;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcHealthChangeEventDeliveryAdapter;
import com.personal.baton.watch.application.monitoring.port.in.EventDeliveryMaintenanceUseCase;
import com.personal.baton.watch.application.monitoring.port.in.RunEventDeliveriesUseCase;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import com.personal.baton.watch.application.monitoring.service.EventDeliveryMaintenanceService;
import com.personal.baton.watch.application.monitoring.service.EventDeliveryRetryPolicy;
import com.personal.baton.watch.application.monitoring.service.RunEventDeliveriesService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

@Configuration(proxyBeanMethods = false)
class EventDeliveryConfiguration {

    @Bean
    HealthChangeEventDeliveryPersistencePort healthChangeEventDeliveryPersistence(
            JdbcClient jdbcClient, TransactionOperations transactions, MonitoringMetrics metrics) {
        return new MeteredHealthChangeEventDeliveryPersistence(
                new JdbcHealthChangeEventDeliveryAdapter(jdbcClient, transactions), metrics);
    }

    @Bean
    @Conditional(EnabledCondition.class)
    ApacheHealthChangeEventSender healthChangeEventSender(
            EventDeliveryProperties properties,
            WatchProperties watchProperties,
            Clock clock) {
        if (properties.bearerToken().equals(watchProperties.apiToken())) {
            throw new IllegalArgumentException("event delivery token must differ from the monitor API token");
        }
        EventDeliveryProperties.Http http = properties.http();
        EventDeliveryLimits limits = new EventDeliveryLimits(
                http.connectTimeout(),
                http.responseTimeout(),
                http.totalTimeout(),
                http.maxResponseBytes(),
                http.maxHeaderCount(),
                http.maxHeaderLineLength());
        return new ApacheHealthChangeEventSender(
                properties.endpointUri(),
                properties.bearerToken(),
                limits,
                http.dnsThreads(),
                http.dnsQueueCapacity(),
                http.requestThreads(),
                http.requestQueueCapacity(),
                clock);
    }

    @Bean
    @Primary
    @Conditional(EnabledCondition.class)
    HealthChangeEventSender meteredHealthChangeEventSender(
            ApacheHealthChangeEventSender sender, MonitoringMetrics metrics) {
        return new MeteredHealthChangeEventSender(sender, metrics);
    }

    @Bean
    @Conditional(EnabledCondition.class)
    RunEventDeliveriesUseCase runEventDeliveriesUseCase(
            HealthChangeEventDeliveryPersistencePort persistence,
            HealthChangeEventSender sender,
            Clock clock,
            EventDeliveryProperties properties,
            WatchProperties watchProperties,
            DatabaseRuntimeProperties database,
            PersistenceProperties persistenceProperties) {
        WorkerExecutionBudget.requireSafe(
                "event delivery",
                watchProperties.workerExecutionBudget(),
                properties.leaseDuration(),
                properties.http().totalTimeout(),
                properties.batchSize(),
                database,
                persistenceProperties);
        return new RunEventDeliveriesService(
                persistence,
                sender,
                clock,
                properties.leaseDuration(),
                new EventDeliveryRetryPolicy(
                        properties.initialRetryDelay(), properties.maxRetryDelay()),
                properties.batchSize());
    }

    @Bean
    EventDeliveryMaintenanceUseCase eventDeliveryMaintenanceUseCase(
            HealthChangeEventDeliveryPersistencePort persistence,
            Clock clock,
            EventDeliveryProperties properties,
            WatchProperties watchProperties) {
        return new EventDeliveryMaintenanceService(
                persistence, clock, properties.retention(), watchProperties.maintenanceBatchSize());
    }

    static final class EnabledCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getEnvironment().getProperty("watch.event-delivery.enabled", Boolean.class, false);
        }
    }
}

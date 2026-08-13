package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.adapter.out.external.delivery.ApacheHealthChangeEventSender;
import com.personal.baton.watch.adapter.out.external.delivery.EventDeliveryLimits;
import com.personal.baton.watch.adapter.out.persistence.monitoring.JdbcHealthChangeEventDeliveryAdapter;
import com.personal.baton.watch.application.monitoring.port.in.GetEventDeliveryBacklogUseCase;
import com.personal.baton.watch.application.monitoring.port.in.PurgeDeliveredEventsUseCase;
import com.personal.baton.watch.application.monitoring.port.in.RunEventDeliveriesUseCase;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventDeliveryPersistencePort;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import com.personal.baton.watch.application.monitoring.service.EventDeliveryRetryPolicy;
import com.personal.baton.watch.application.monitoring.service.GetEventDeliveryBacklogService;
import com.personal.baton.watch.application.monitoring.service.PurgeDeliveredEventsService;
import com.personal.baton.watch.application.monitoring.service.RunEventDeliveriesService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class EventDeliveryConfiguration {

    @Bean
    EventDeliveryRetryPolicy eventDeliveryRetryPolicy(EventDeliveryProperties properties) {
        return new EventDeliveryRetryPolicy(
                properties.initialRetryDelay(), properties.maxRetryDelay());
    }

    @Bean
    JdbcHealthChangeEventDeliveryAdapter healthChangeEventDeliveryPersistenceAdapter(
            JdbcClient jdbcClient, TransactionOperations transactions) {
        return new JdbcHealthChangeEventDeliveryAdapter(jdbcClient, transactions);
    }

    @Bean
    @ConditionalOnBooleanProperty(prefix = "watch.event-delivery", name = "enabled")
    ApacheHealthChangeEventSender healthChangeEventSender(
            EventDeliveryProperties properties,
            WatchProperties watchProperties,
            ObjectMapper objectMapper) {
        requireSeparateToken(properties.bearerToken(), watchProperties.apiToken());
        EventDeliveryProperties.Http http = properties.http();
        EventDeliveryLimits limits = new EventDeliveryLimits(
                http.connectTimeout(),
                http.responseTimeout(),
                http.totalTimeout(),
                http.maxResponseBytes(),
                http.maxHeaderCount(),
                http.maxHeaderLineLength());
        return new ApacheHealthChangeEventSender(
                properties.endpoint(),
                properties.bearerToken(),
                limits,
                http.dnsThreads(),
                http.dnsQueueCapacity(),
                http.requestThreads(),
                http.requestQueueCapacity(),
                objectMapper);
    }

    @Bean
    @Primary
    @ConditionalOnBooleanProperty(prefix = "watch.event-delivery", name = "enabled")
    HealthChangeEventSender meteredHealthChangeEventSender(
            ApacheHealthChangeEventSender sender, MonitoringMetrics metrics) {
        return new MeteredHealthChangeEventSender(sender, metrics);
    }

    @Bean
    @ConditionalOnBooleanProperty(prefix = "watch.event-delivery", name = "enabled")
    RunEventDeliveriesUseCase runEventDeliveriesUseCase(
            HealthChangeEventDeliveryPersistencePort persistence,
            HealthChangeEventSender sender,
            Clock clock,
            EventDeliveryProperties properties,
            EventDeliveryRetryPolicy retryPolicy) {
        return new RunEventDeliveriesService(
                persistence,
                sender,
                clock,
                properties.leaseDuration(),
                retryPolicy,
                properties.batchSize());
    }

    @Bean
    PurgeDeliveredEventsUseCase purgeDeliveredEventsUseCase(
            HealthChangeEventDeliveryPersistencePort persistence,
            Clock clock,
            EventDeliveryProperties properties) {
        return new PurgeDeliveredEventsService(
                persistence, clock, properties.retention(), properties.maintenanceBatchSize());
    }

    @Bean
    GetEventDeliveryBacklogUseCase getEventDeliveryBacklogUseCase(
            HealthChangeEventDeliveryPersistencePort persistence, Clock clock) {
        return new GetEventDeliveryBacklogService(persistence, clock);
    }

    static void requireSeparateToken(String deliveryToken, String monitorApiToken) {
        if (deliveryToken.equals(monitorApiToken)) {
            throw new IllegalArgumentException("event delivery token must differ from the monitor API token");
        }
    }
}

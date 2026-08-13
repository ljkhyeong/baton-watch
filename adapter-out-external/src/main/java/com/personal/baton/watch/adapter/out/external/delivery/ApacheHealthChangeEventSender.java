package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import com.personal.baton.watch.adapter.out.external.check.BoundedDnsLookup;
import com.personal.baton.watch.adapter.out.external.check.GlobalAddressPolicy;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import java.net.URI;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/** 고정 BATON 상태 변경 콜백 엔드포인트용 운영 송신자. */
public final class ApacheHealthChangeEventSender implements HealthChangeEventSender, AutoCloseable {

    private final SafeEventDeliveryEngine engine;
    private final AutoCloseable dnsLookup;
    private final AutoCloseable transport;

    public ApacheHealthChangeEventSender(
            URI endpoint,
            String bearerToken,
            EventDeliveryLimits limits,
            int dnsThreadCount,
            int dnsQueueCapacity,
            int httpThreadCount,
            int httpQueueCapacity,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(limits, "limits");
        ValidatedDeliveryEndpoint validatedEndpoint = new DeliveryEndpointPolicy().validate(endpoint);
        String validatedToken = validateBearerToken(bearerToken);
        OutboundResourceBounds.requireDnsExecutorBounds(dnsThreadCount, dnsQueueCapacity);
        OutboundResourceBounds.requireRequestExecutorBounds(httpThreadCount, httpQueueCapacity);
        HealthChangeEventJsonSerializer serializer = new HealthChangeEventJsonSerializer(objectMapper);
        BoundedDnsLookup boundedDnsLookup = new BoundedDnsLookup(dnsThreadCount, dnsQueueCapacity);
        ApacheEventDeliveryTransport apacheTransport =
                new ApacheEventDeliveryTransport(limits, httpThreadCount, httpQueueCapacity);
        this.engine = new SafeEventDeliveryEngine(
                validatedEndpoint,
                validatedToken,
                limits,
                boundedDnsLookup,
                new GlobalAddressPolicy(),
                apacheTransport,
                serializer,
                System::nanoTime);
        this.dnsLookup = boundedDnsLookup;
        this.transport = apacheTransport;
    }

    @Override
    public EventDeliveryObservation send(HealthChangeEventPayload payload) {
        return engine.send(payload);
    }

    @Override
    public void close() {
        closeQuietly(transport);
        closeQuietly(dnsLookup);
    }

    private static String validateBearerToken(String bearerToken) {
        Objects.requireNonNull(bearerToken, "bearerToken");
        if (bearerToken.length() < 32
                || bearerToken.codePoints().anyMatch(codePoint -> codePoint < 0x21 || codePoint > 0x7e)) {
            throw new IllegalArgumentException(
                    "event delivery bearer token must contain at least 32 safe characters");
        }
        return bearerToken;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 종료는 최선을 다해 시도하며 예외 세부 정보를 의도적으로 노출하지 않는다.
        }
    }
}

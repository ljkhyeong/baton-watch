package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import com.personal.baton.watch.adapter.out.external.check.BoundedDnsLookup;
import com.personal.baton.watch.adapter.out.external.check.GlobalAddressPolicy;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/** 고정 BATON 상태 변경 콜백 엔드포인트용 운영 송신자. */
public final class ApacheHealthChangeEventSender implements HealthChangeEventSender, AutoCloseable {

    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9._~-]{32,200}");

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
        try (dnsLookup; transport) {
            // 등록 역순인 전송 계층, DNS 조회기 순서로 닫는다.
        } catch (Exception ignored) {
            // 종료는 최선을 다해 시도하며 예외 세부 정보를 의도적으로 노출하지 않는다.
        }
    }

    private static String validateBearerToken(String bearerToken) {
        if (!BEARER_TOKEN.matcher(bearerToken).matches()) {
            throw new IllegalArgumentException(
                    "event delivery bearer token must contain 32 to 200 URL-safe characters");
        }
        return bearerToken;
    }

}

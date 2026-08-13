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

/** Production sender for the fixed BATON health-change callback endpoint. */
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
        ValidatedDeliveryEndpoint validatedEndpoint = validateEndpoint(endpoint);
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

    private static ValidatedDeliveryEndpoint validateEndpoint(URI endpoint) {
        try {
            return new DeliveryEndpointPolicy().validate(endpoint);
        } catch (DeliveryPolicyException exception) {
            throw new IllegalArgumentException("event delivery endpoint violates policy");
        }
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
            // Shutdown is best effort and intentionally does not expose exception details.
        }
    }
}

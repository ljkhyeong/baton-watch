package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.check.BoundedDnsLookup;
import com.personal.baton.watch.adapter.out.external.check.GlobalAddressPolicy;
import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import java.net.URI;
import java.util.Objects;

/** Production sender for the fixed BATON health-change callback endpoint. */
public final class ApacheHealthChangeEventSender implements HealthChangeEventSender, AutoCloseable {

    private static final int DNS_THREADS = 2;
    private static final int DNS_QUEUE_CAPACITY = 8;
    private static final int HTTP_THREADS = 1;
    private static final int HTTP_QUEUE_CAPACITY = 1;

    private final SafeEventDeliveryEngine engine;
    private final AutoCloseable dnsLookup;
    private final AutoCloseable transport;

    public ApacheHealthChangeEventSender(
            URI endpoint, String bearerToken, EventDeliveryLimits limits) {
        this(
                endpoint,
                bearerToken,
                limits,
                DNS_THREADS,
                DNS_QUEUE_CAPACITY,
                HTTP_THREADS,
                HTTP_QUEUE_CAPACITY);
    }

    public ApacheHealthChangeEventSender(
            URI endpoint,
            String bearerToken,
            EventDeliveryLimits limits,
            int dnsThreadCount,
            int dnsQueueCapacity,
            int httpThreadCount,
            int httpQueueCapacity) {
        Objects.requireNonNull(limits, "limits");
        ValidatedDeliveryEndpoint validatedEndpoint = validateEndpoint(endpoint);
        String validatedToken = validateBearerToken(bearerToken);
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
                new HealthChangeEventJson(),
                System::nanoTime);
        this.dnsLookup = boundedDnsLookup;
        this.transport = apacheTransport;
    }

    ApacheHealthChangeEventSender(
            SafeEventDeliveryEngine engine, AutoCloseable dnsLookup, AutoCloseable transport) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.dnsLookup = Objects.requireNonNull(dnsLookup, "dnsLookup");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public EventDeliveryObservation send(ClaimedHealthChangeEvent event) {
        return engine.send(event);
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
                || bearerToken.isBlank()
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

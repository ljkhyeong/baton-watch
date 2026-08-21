package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.check.AddressPolicyException;
import com.personal.baton.watch.adapter.out.external.check.DnsLookup;
import com.personal.baton.watch.adapter.out.external.check.DnsLookupException;
import com.personal.baton.watch.adapter.out.external.check.GlobalAddressPolicy;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

final class SafeEventDeliveryEngine {

    private final ValidatedDeliveryEndpoint endpoint;
    private final String bearerToken;
    private final EventDeliveryLimits limits;
    private final DnsLookup dnsLookup;
    private final GlobalAddressPolicy addressPolicy;
    private final DeliveryTransport transport;
    private final Function<HealthChangeEventPayload, byte[]> serializer;
    private final LongSupplier clock;

    SafeEventDeliveryEngine(
            ValidatedDeliveryEndpoint endpoint,
            String bearerToken,
            EventDeliveryLimits limits,
            DnsLookup dnsLookup,
            GlobalAddressPolicy addressPolicy,
            DeliveryTransport transport,
            Function<HealthChangeEventPayload, byte[]> serializer,
            LongSupplier clock) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.bearerToken = Objects.requireNonNull(bearerToken, "bearerToken");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.dnsLookup = Objects.requireNonNull(dnsLookup, "dnsLookup");
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    EventDeliveryObservation send(HealthChangeEventPayload payload) {
        long startedAt = clock.getAsLong();
        try {
            byte[] body = serializer.apply(payload);
            Duration remaining = remaining(startedAt);
            if (remaining.isZero()) {
                return EventDeliveryObservation.failure(EventDeliveryOutcome.DNS_FAILURE);
            }

            List<InetAddress> resolved;
            try {
                resolved = dnsLookup.resolve(endpoint.hostname(), remaining);
            } catch (DnsLookupException exception) {
                return switch (exception.reason()) {
                    case DNS_FAILURE -> EventDeliveryObservation.failure(
                            EventDeliveryOutcome.DNS_FAILURE);
                    case INTERNAL_FAILURE -> EventDeliveryObservation.internalFailure();
                };
            }

            List<InetAddress> approved;
            try {
                approved = addressPolicy.approve(resolved);
            } catch (AddressPolicyException exception) {
                return EventDeliveryObservation.failure(EventDeliveryOutcome.DESTINATION_REJECTED);
            }

            remaining = remaining(startedAt);
            if (remaining.isZero()) {
                return EventDeliveryObservation.failure(EventDeliveryOutcome.DNS_FAILURE);
            }

            ApprovedDeliveryRequest request = new ApprovedDeliveryRequest(
                    endpoint,
                    approved,
                    body,
                    bearerToken,
                    payload.eventId().toString());
            int statusCode;
            try {
                statusCode = transport.execute(request, remaining);
            } catch (OutboundHttpFailure exception) {
                return transportFailure(exception.kind());
            }
            if (statusCode < 200 || statusCode > 599) {
                return EventDeliveryObservation.failure(EventDeliveryOutcome.NETWORK_FAILURE);
            }
            return EventDeliveryObservation.forHttpStatus(statusCode);
        } catch (RuntimeException exception) {
            return EventDeliveryObservation.internalFailure();
        }
    }

    private EventDeliveryObservation transportFailure(OutboundHttpFailure.Kind kind) {
        EventDeliveryOutcome outcome = switch (kind) {
            case CONNECT_TIMEOUT -> EventDeliveryOutcome.CONNECT_TIMEOUT;
            case READ_TIMEOUT -> EventDeliveryOutcome.READ_TIMEOUT;
            case TLS_FAILURE -> EventDeliveryOutcome.TLS_FAILURE;
            case RESPONSE_TOO_LARGE -> EventDeliveryOutcome.RESPONSE_TOO_LARGE;
            case NETWORK_FAILURE -> EventDeliveryOutcome.NETWORK_FAILURE;
            case INTERNAL_FAILURE -> EventDeliveryOutcome.INTERNAL_FAILURE;
        };
        return EventDeliveryObservation.failure(outcome);
    }

    private Duration remaining(long startedAt) {
        long elapsed = Math.max(0, clock.getAsLong() - startedAt);
        long remaining = limits.totalTimeout().toNanos() - elapsed;
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }
}

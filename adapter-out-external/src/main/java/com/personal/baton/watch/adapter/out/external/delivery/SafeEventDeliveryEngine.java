package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.check.AddressPolicyException;
import com.personal.baton.watch.adapter.out.external.check.DnsLookup;
import com.personal.baton.watch.adapter.out.external.check.DnsLookupException;
import com.personal.baton.watch.adapter.out.external.check.GlobalAddressPolicy;
import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

final class SafeEventDeliveryEngine {

    private final ValidatedDeliveryEndpoint endpoint;
    private final String bearerToken;
    private final EventDeliveryLimits limits;
    private final DnsLookup dnsLookup;
    private final GlobalAddressPolicy addressPolicy;
    private final DeliveryTransport transport;
    private final HealthChangeEventJson serializer;
    private final DeliveryMonotonicClock clock;

    SafeEventDeliveryEngine(
            ValidatedDeliveryEndpoint endpoint,
            String bearerToken,
            EventDeliveryLimits limits,
            DnsLookup dnsLookup,
            GlobalAddressPolicy addressPolicy,
            DeliveryTransport transport,
            HealthChangeEventJson serializer,
            DeliveryMonotonicClock clock) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.bearerToken = Objects.requireNonNull(bearerToken, "bearerToken");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.dnsLookup = Objects.requireNonNull(dnsLookup, "dnsLookup");
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    EventDeliveryObservation send(ClaimedHealthChangeEvent event) {
        long startedAt = clock.nanoTime();
        try {
            if (event == null) {
                return EventDeliveryObservation.internalFailure();
            }
            byte[] payload = serializer.serialize(event);
            Duration remaining = remaining(startedAt);
            if (remaining.isZero()) {
                return failure(EventDeliveryOutcome.DNS_FAILURE);
            }

            List<InetAddress> resolved;
            try {
                resolved = dnsLookup.resolve(endpoint.hostname(), remaining);
            } catch (DnsLookupException exception) {
                return switch (exception.reason()) {
                    case NOT_FOUND, TIMED_OUT -> failure(EventDeliveryOutcome.DNS_FAILURE);
                    case CAPACITY_EXHAUSTED, INTERRUPTED, FAILED ->
                            EventDeliveryObservation.internalFailure();
                };
            }

            List<InetAddress> approved;
            try {
                approved = addressPolicy.approve(resolved);
            } catch (AddressPolicyException exception) {
                return failure(EventDeliveryOutcome.DESTINATION_REJECTED);
            }

            remaining = remaining(startedAt);
            if (remaining.isZero()) {
                return failure(EventDeliveryOutcome.DNS_FAILURE);
            }

            ApprovedDeliveryRequest request = new ApprovedDeliveryRequest(
                    endpoint,
                    approved,
                    payload,
                    bearerToken,
                    event.eventId().toString());
            DeliveryHttpResponse response;
            try {
                response = transport.execute(request, remaining);
            } catch (DeliveryTransportFailure exception) {
                return transportFailure(exception.kind());
            }
            if (response.statusCode() < 200 || response.statusCode() > 599) {
                return failure(EventDeliveryOutcome.NETWORK_FAILURE);
            }
            return EventDeliveryObservation.forHttpStatus(response.statusCode());
        } catch (RuntimeException exception) {
            return EventDeliveryObservation.internalFailure();
        }
    }

    private EventDeliveryObservation transportFailure(DeliveryTransportFailure.Kind kind) {
        EventDeliveryOutcome outcome = switch (kind) {
            case CONNECT_TIMEOUT -> EventDeliveryOutcome.CONNECT_TIMEOUT;
            case READ_TIMEOUT -> EventDeliveryOutcome.READ_TIMEOUT;
            case TLS_FAILURE -> EventDeliveryOutcome.TLS_FAILURE;
            case RESPONSE_TOO_LARGE -> EventDeliveryOutcome.RESPONSE_TOO_LARGE;
            case NETWORK_FAILURE -> EventDeliveryOutcome.NETWORK_FAILURE;
            case INTERNAL_FAILURE -> EventDeliveryOutcome.INTERNAL_FAILURE;
        };
        return failure(outcome);
    }

    private EventDeliveryObservation failure(EventDeliveryOutcome outcome) {
        return EventDeliveryObservation.failure(outcome);
    }

    private Duration remaining(long startedAt) {
        long elapsed = Math.max(0, clock.nanoTime() - startedAt);
        long remaining = limits.totalTimeoutNanos() - elapsed;
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }
}

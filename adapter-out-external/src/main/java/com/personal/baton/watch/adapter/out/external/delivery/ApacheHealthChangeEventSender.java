package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.check.BoundedDnsLookup;
import com.personal.baton.watch.adapter.out.external.check.DnsLookup;
import com.personal.baton.watch.adapter.out.external.check.GlobalAddressPolicy;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.application.monitoring.port.out.HealthChangeEventSender;
import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** 고정 BATON 상태 변경 콜백 엔드포인트용 운영 송신자. 제한된 DNS 및 HTTP 실행기를 소유한다. */
public final class ApacheHealthChangeEventSender implements HealthChangeEventSender, AutoCloseable {

    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9._~-]{32,200}");

    private final String hostname;
    private final EventDeliveryLimits limits;
    private final Function<HealthChangeEventPayload, byte[]> serializer;
    private final GlobalAddressPolicy addressPolicy = new GlobalAddressPolicy();
    private final DnsLookup dnsLookup;
    private final DeliveryTransport transport;
    private final LongSupplier clock;

    public ApacheHealthChangeEventSender(
            URI endpoint,
            String bearerToken,
            EventDeliveryLimits limits,
            int dnsThreadCount,
            int dnsQueueCapacity,
            int httpThreadCount,
            int httpQueueCapacity,
            Clock clock) {
        // 인자는 왼쪽부터 평가되므로 엔드포인트·토큰 검증이 실행기 생성보다 먼저 끝난다.
        this(
                validatedHostname(endpoint, bearerToken),
                limits,
                HealthChangeEventRequest::serialize,
                new BoundedDnsLookup(dnsThreadCount, dnsQueueCapacity),
                new ApacheEventDeliveryTransport(
                        endpoint, bearerToken, limits, httpThreadCount, httpQueueCapacity, clock),
                System::nanoTime);
    }

    ApacheHealthChangeEventSender(
            String hostname,
            EventDeliveryLimits limits,
            Function<HealthChangeEventPayload, byte[]> serializer,
            DnsLookup dnsLookup,
            DeliveryTransport transport,
            LongSupplier clock) {
        this.hostname = Objects.requireNonNull(hostname, "hostname");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.dnsLookup = Objects.requireNonNull(dnsLookup, "dnsLookup");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public EventDeliveryObservation send(HealthChangeEventPayload payload) {
        long startedAt = clock.getAsLong();
        try {
            byte[] body = serializer.apply(payload);
            Duration remaining = remaining(startedAt);
            if (remaining.isZero()) {
                return EventDeliveryObservation.failure(EventDeliveryOutcome.CONNECT_TIMEOUT);
            }

            List<InetAddress> approved = addressPolicy.approve(dnsLookup.resolve(hostname, remaining));

            remaining = remaining(startedAt);
            if (remaining.isZero()) {
                return EventDeliveryObservation.failure(EventDeliveryOutcome.DNS_FAILURE);
            }

            DeliveryResponse response =
                    transport.execute(approved, body, payload.eventId().toString(), remaining);
            if (response.statusCode() < 200 || response.statusCode() > 599) {
                return EventDeliveryObservation.failure(EventDeliveryOutcome.NETWORK_FAILURE);
            }
            return EventDeliveryObservation.forHttpStatus(response.statusCode(), response.retryNotBefore());
        } catch (OutboundHttpFailure exception) {
            return EventDeliveryObservation.failure(outcome(exception.kind()));
        } catch (RuntimeException exception) {
            return EventDeliveryObservation.internalFailure();
        }
    }

    @Override
    public void close() {
        try (dnsLookup; transport) {
            // 등록 역순인 전송 계층, DNS 조회기 순서로 닫는다.
        } catch (RuntimeException ignored) {
            // 종료는 최선을 다해 시도하며 예외 세부 정보를 의도적으로 노출하지 않는다.
        }
    }

    private static EventDeliveryOutcome outcome(OutboundHttpFailure.Kind kind) {
        return switch (kind) {
            case DESTINATION_REJECTED -> EventDeliveryOutcome.DESTINATION_REJECTED;
            case DNS_FAILURE -> EventDeliveryOutcome.DNS_FAILURE;
            case CONNECT_TIMEOUT -> EventDeliveryOutcome.CONNECT_TIMEOUT;
            case READ_TIMEOUT -> EventDeliveryOutcome.READ_TIMEOUT;
            case TLS_FAILURE -> EventDeliveryOutcome.TLS_FAILURE;
            case RESPONSE_TOO_LARGE -> EventDeliveryOutcome.RESPONSE_TOO_LARGE;
            case NETWORK_FAILURE -> EventDeliveryOutcome.NETWORK_FAILURE;
            case INTERNAL_FAILURE -> EventDeliveryOutcome.INTERNAL_FAILURE;
        };
    }

    private Duration remaining(long startedAt) {
        long elapsed = Math.max(0, clock.getAsLong() - startedAt);
        long remaining = limits.totalTimeout().toNanos() - elapsed;
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    private static String validatedHostname(URI endpoint, String bearerToken) {
        String hostname = new DeliveryEndpointPolicy().validate(endpoint).getHost();
        if (!BEARER_TOKEN.matcher(bearerToken).matches()) {
            throw new IllegalArgumentException(
                    "event delivery bearer token must contain 32 to 200 URL-safe characters");
        }
        return hostname;
    }
}

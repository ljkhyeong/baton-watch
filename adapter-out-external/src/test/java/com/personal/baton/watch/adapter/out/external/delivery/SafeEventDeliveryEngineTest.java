package com.personal.baton.watch.adapter.out.external.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.adapter.out.external.check.DnsLookup;
import com.personal.baton.watch.adapter.out.external.check.DnsLookupException;
import com.personal.baton.watch.adapter.out.external.check.GlobalAddressPolicy;
import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SafeEventDeliveryEngineTest {

    @Test
    void resolvesEveryAttemptAndPassesOnlyApprovedPinnedAddressesToTheTransport() throws Exception {
        RecordingDnsLookup dns = new RecordingDnsLookup(List.of(
                address("8.8.8.8"), address("1.1.1.1")));
        RecordingTransport transport = new RecordingTransport(new DeliveryHttpResponse(204, 0));
        SafeEventDeliveryEngine engine = engine(dns, transport, System::nanoTime);

        EventDeliveryObservation first = engine.send(event());
        EventDeliveryObservation second = engine.send(event());

        assertEquals(EventDeliveryOutcome.DELIVERED, first.outcome());
        assertEquals(204, first.httpStatusCode());
        assertEquals(EventDeliveryOutcome.DELIVERED, second.outcome());
        assertEquals(2, dns.calls);
        assertEquals("events.example.com", dns.lastHostname);
        assertNotNull(transport.lastRequest);
        assertEquals(List.of(address("8.8.8.8"), address("1.1.1.1")), transport.lastRequest.addresses());
        assertEquals("delivery-token", transport.lastRequest.bearerToken());
        assertEquals("00000000-0000-0000-0000-000000000001", transport.lastRequest.idempotencyKey());
        String payload = new String(transport.lastRequest.payload(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"eventType\":\"RESOURCE_HEALTH_CHANGED\""));
        assertTrue(payload.contains("\"eventId\":\"00000000-0000-0000-0000-000000000001\""));
    }

    @Test
    void rejectsTheEntireDnsAnswerWhenOneAddressIsNotPublicGlobal() throws Exception {
        RecordingDnsLookup dns = new RecordingDnsLookup(List.of(
                address("8.8.8.8"), address("127.0.0.1")));
        RecordingTransport transport = new RecordingTransport(new DeliveryHttpResponse(204, 0));

        EventDeliveryObservation observation = engine(dns, transport, System::nanoTime).send(event());

        assertEquals(EventDeliveryOutcome.DESTINATION_REJECTED, observation.outcome());
        assertNull(observation.httpStatusCode());
        assertNull(transport.lastRequest);
    }

    @ParameterizedTest
    @MethodSource("dnsFailures")
    void mapsDnsFailuresToBoundedOutcomes(
            DnsLookupException.Reason reason, EventDeliveryOutcome expected) {
        DnsLookup dns = (hostname, timeout) -> {
            throw new DnsLookupException(reason);
        };
        RecordingTransport transport = new RecordingTransport(new DeliveryHttpResponse(204, 0));

        EventDeliveryObservation observation = engine(dns, transport, System::nanoTime).send(event());

        assertEquals(expected, observation.outcome());
        assertNull(observation.httpStatusCode());
        assertNull(transport.lastRequest);
    }

    @ParameterizedTest
    @MethodSource("httpStatuses")
    void mapsFinalHttpStatusesWithoutFollowingRedirects(
            int status, EventDeliveryOutcome expected) throws Exception {
        RecordingTransport transport = new RecordingTransport(new DeliveryHttpResponse(status, 0));

        EventDeliveryObservation observation = engine(
                        new RecordingDnsLookup(List.of(address("8.8.8.8"))),
                        transport,
                        System::nanoTime)
                .send(event());

        assertEquals(expected, observation.outcome());
        assertEquals(status, observation.httpStatusCode());
    }

    @Test
    void mapsUnsupportedFinalHttpMetadataToNetworkFailure() throws Exception {
        RecordingTransport transport = new RecordingTransport(new DeliveryHttpResponse(101, 0));

        EventDeliveryObservation observation = engine(
                        new RecordingDnsLookup(List.of(address("8.8.8.8"))),
                        transport,
                        System::nanoTime)
                .send(event());

        assertEquals(EventDeliveryOutcome.NETWORK_FAILURE, observation.outcome());
        assertNull(observation.httpStatusCode());
    }

    @ParameterizedTest
    @MethodSource("transportFailures")
    void mapsTransportFailuresToBoundedOutcomes(
            DeliveryTransportFailure.Kind kind, EventDeliveryOutcome expected) throws Exception {
        DeliveryTransport transport = (request, remaining) -> {
            throw new DeliveryTransportFailure(kind);
        };

        EventDeliveryObservation observation = engine(
                        new RecordingDnsLookup(List.of(address("8.8.8.8"))),
                        transport,
                        System::nanoTime)
                .send(event());

        assertEquals(expected, observation.outcome());
        assertNull(observation.httpStatusCode());
    }

    @Test
    void includesDnsResolutionInTheTotalDeadline() throws Exception {
        MutableClock clock = new MutableClock();
        InetAddress publicAddress = address("8.8.8.8");
        DnsLookup dns = (hostname, timeout) -> {
            clock.advance(EventDeliveryLimits.DEFAULTS.totalTimeout());
            return List.of(publicAddress);
        };
        RecordingTransport transport = new RecordingTransport(new DeliveryHttpResponse(204, 0));

        EventDeliveryObservation observation = engine(dns, transport, clock).send(event());

        assertEquals(EventDeliveryOutcome.DNS_FAILURE, observation.outcome());
        assertNull(transport.lastRequest);
    }

    @Test
    void convertsUnexpectedAdapterFailuresToInternalFailure() {
        DnsLookup dns = (hostname, timeout) -> {
            throw new IllegalStateException("sensitive resolver detail");
        };

        EventDeliveryObservation observation =
                engine(dns, (request, remaining) -> null, System::nanoTime).send(event());

        assertEquals(EventDeliveryOutcome.INTERNAL_FAILURE, observation.outcome());
        assertNull(observation.httpStatusCode());
    }

    private static SafeEventDeliveryEngine engine(
            DnsLookup dns, DeliveryTransport transport, DeliveryMonotonicClock clock) {
        return new SafeEventDeliveryEngine(
                new ValidatedDeliveryEndpoint(
                        URI.create("https://events.example.com/api/v1/health-events"),
                        "events.example.com"),
                "delivery-token",
                EventDeliveryLimits.DEFAULTS,
                dns,
                new GlobalAddressPolicy(),
                transport,
                new HealthChangeEventJson(),
                clock);
    }

    private static ClaimedHealthChangeEvent event() {
        return new ClaimedHealthChangeEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new ResourceReference("role-resource-123"),
                new SourceRevision(42),
                Optional.of(UUID.fromString("00000000-0000-0000-0000-000000000003")),
                Health.DEGRADED,
                Health.BROKEN,
                Instant.parse("2026-08-02T01:02:03.456Z"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                7);
    }

    private static InetAddress address(String value) throws Exception {
        return InetAddress.getByName(value);
    }

    private static Stream<Arguments> dnsFailures() {
        return Stream.of(
                Arguments.of(DnsLookupException.Reason.NOT_FOUND, EventDeliveryOutcome.DNS_FAILURE),
                Arguments.of(DnsLookupException.Reason.TIMED_OUT, EventDeliveryOutcome.DNS_FAILURE),
                Arguments.of(
                        DnsLookupException.Reason.CAPACITY_EXHAUSTED,
                        EventDeliveryOutcome.INTERNAL_FAILURE),
                Arguments.of(DnsLookupException.Reason.INTERRUPTED, EventDeliveryOutcome.INTERNAL_FAILURE),
                Arguments.of(DnsLookupException.Reason.FAILED, EventDeliveryOutcome.INTERNAL_FAILURE));
    }

    private static Stream<Arguments> httpStatuses() {
        return Stream.of(
                Arguments.of(200, EventDeliveryOutcome.DELIVERED),
                Arguments.of(299, EventDeliveryOutcome.DELIVERED),
                Arguments.of(302, EventDeliveryOutcome.HTTP_CLIENT_ERROR),
                Arguments.of(404, EventDeliveryOutcome.HTTP_CLIENT_ERROR),
                Arguments.of(500, EventDeliveryOutcome.HTTP_SERVER_ERROR),
                Arguments.of(599, EventDeliveryOutcome.HTTP_SERVER_ERROR));
    }

    private static Stream<Arguments> transportFailures() {
        return Stream.of(
                Arguments.of(
                        DeliveryTransportFailure.Kind.CONNECT_TIMEOUT,
                        EventDeliveryOutcome.CONNECT_TIMEOUT),
                Arguments.of(DeliveryTransportFailure.Kind.READ_TIMEOUT, EventDeliveryOutcome.READ_TIMEOUT),
                Arguments.of(DeliveryTransportFailure.Kind.TLS_FAILURE, EventDeliveryOutcome.TLS_FAILURE),
                Arguments.of(
                        DeliveryTransportFailure.Kind.RESPONSE_TOO_LARGE,
                        EventDeliveryOutcome.RESPONSE_TOO_LARGE),
                Arguments.of(DeliveryTransportFailure.Kind.NETWORK_FAILURE, EventDeliveryOutcome.NETWORK_FAILURE),
                Arguments.of(DeliveryTransportFailure.Kind.INTERNAL_FAILURE, EventDeliveryOutcome.INTERNAL_FAILURE));
    }

    private static final class RecordingDnsLookup implements DnsLookup {

        private final List<InetAddress> answer;
        private int calls;
        private String lastHostname;

        private RecordingDnsLookup(List<InetAddress> answer) {
            this.answer = answer;
        }

        @Override
        public List<InetAddress> resolve(String hostname, Duration timeout) {
            calls++;
            lastHostname = hostname;
            return answer;
        }
    }

    private static final class RecordingTransport implements DeliveryTransport {

        private final DeliveryHttpResponse response;
        private ApprovedDeliveryRequest lastRequest;

        private RecordingTransport(DeliveryHttpResponse response) {
            this.response = response;
        }

        @Override
        public DeliveryHttpResponse execute(ApprovedDeliveryRequest request, Duration remainingTime) {
            lastRequest = request;
            return response;
        }
    }

    private static final class MutableClock implements DeliveryMonotonicClock {

        private long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }
    }
}

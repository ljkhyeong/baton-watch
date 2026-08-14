package com.personal.baton.watch.adapter.out.external.delivery;

import static com.personal.baton.watch.adapter.out.external.delivery.EventDeliveryTestFixtures.DEFAULT_LIMITS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.adapter.out.external.check.DnsLookup;
import com.personal.baton.watch.adapter.out.external.check.DnsLookupException;
import com.personal.baton.watch.adapter.out.external.check.GlobalAddressPolicy;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class SafeEventDeliveryEngineTest {

    @Test
    void resolvesEveryAttemptAndPassesOnlyApprovedPinnedAddressesToTheTransport() throws Exception {
        RecordingDnsLookup dns = new RecordingDnsLookup(List.of(
                address("8.8.8.8"), address("1.1.1.1")));
        RecordingTransport transport = new RecordingTransport(204);
        SafeEventDeliveryEngine engine = engine(dns, transport, System::nanoTime);

        EventDeliveryObservation first = engine.send(event());
        EventDeliveryObservation second = engine.send(event());

        assertEquals(EventDeliveryOutcome.DELIVERED, first.outcome());
        assertEquals(204, first.httpStatusCode());
        assertEquals(EventDeliveryOutcome.DELIVERED, second.outcome());
        assertEquals(2, dns.calls);
        assertEquals(2, transport.requests.size());
        assertArrayEquals(
                transport.requests.get(0).payload(),
                transport.requests.get(1).payload());
        assertEquals(
                transport.requests.get(0).idempotencyKey(),
                transport.requests.get(1).idempotencyKey());
        assertEquals("events.example.com", dns.lastHostname);
        assertNotNull(transport.lastRequest);
        assertEquals(List.of(address("8.8.8.8"), address("1.1.1.1")), transport.lastRequest.addresses());
        assertEquals("delivery-token", transport.lastRequest.bearerToken());
        assertEquals("00000000-0000-0000-0000-000000000001", transport.lastRequest.idempotencyKey());
        String payload = new String(transport.lastRequest.payload(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"eventType\":\"RESOURCE_HEALTH_CHANGED\""));
        assertTrue(payload.contains("\"eventId\":\"00000000-0000-0000-0000-000000000001\""));
        assertEquals(
                new ObjectMapper().readTree(payload).get("eventId").stringValue(),
                transport.lastRequest.idempotencyKey());
    }

    @Test
    void rejectsTheEntireDnsAnswerWhenOneAddressIsNotPublicGlobal() throws Exception {
        RecordingDnsLookup dns = new RecordingDnsLookup(List.of(
                address("8.8.8.8"), address("127.0.0.1")));
        RecordingTransport transport = new RecordingTransport(204);

        EventDeliveryObservation observation = engine(dns, transport, System::nanoTime).send(event());

        assertEquals(EventDeliveryOutcome.DESTINATION_REJECTED, observation.outcome());
        assertNull(observation.httpStatusCode());
        assertNull(transport.lastRequest);
    }

    @Test
    void rejectsReservedIpv6BeforeCallbackDelivery() throws Exception {
        RecordingDnsLookup dns = new RecordingDnsLookup(List.of(address("3ffe::1")));
        RecordingTransport transport = new RecordingTransport(204);

        EventDeliveryObservation observation = engine(dns, transport, System::nanoTime).send(event());

        assertEquals(EventDeliveryOutcome.DESTINATION_REJECTED, observation.outcome());
        assertNull(observation.httpStatusCode());
        assertNull(transport.lastRequest);
    }

    @Test
    void rejectsAzureWireServerBeforeCallbackDelivery() throws Exception {
        RecordingDnsLookup dns = new RecordingDnsLookup(List.of(address("168.63.129.16")));
        RecordingTransport transport = new RecordingTransport(204);

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
        RecordingTransport transport = new RecordingTransport(204);

        EventDeliveryObservation observation = engine(dns, transport, System::nanoTime).send(event());

        assertEquals(expected, observation.outcome());
        assertNull(observation.httpStatusCode());
        assertNull(transport.lastRequest);
    }

    @ParameterizedTest
    @MethodSource("httpStatuses")
    void mapsFinalHttpStatusesWithoutFollowingRedirects(
            int status, EventDeliveryOutcome expected) throws Exception {
        RecordingTransport transport = new RecordingTransport(status);

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
        RecordingTransport transport = new RecordingTransport(101);

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
            OutboundHttpFailure.Kind kind, EventDeliveryOutcome expected) throws Exception {
        DeliveryTransport transport = (request, remaining) -> {
            throw new OutboundHttpFailure(kind, 0);
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
            clock.advance(DEFAULT_LIMITS.totalTimeout());
            return List.of(publicAddress);
        };
        RecordingTransport transport = new RecordingTransport(204);

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
                engine(dns, (request, remaining) -> 204, System::nanoTime).send(event());

        assertEquals(EventDeliveryOutcome.INTERNAL_FAILURE, observation.outcome());
        assertNull(observation.httpStatusCode());
    }

    @Test
    void serializationFailureStopsBeforeDnsOrTransport() throws Exception {
        RecordingDnsLookup dns = new RecordingDnsLookup(List.of(address("8.8.8.8")));
        RecordingTransport transport = new RecordingTransport(204);
        SafeEventDeliveryEngine engine = engine(
                dns,
                transport,
                System::nanoTime,
                payload -> {
                    throw new IllegalStateException("sensitive serialization detail");
                });

        EventDeliveryObservation observation = engine.send(event());

        assertEquals(EventDeliveryOutcome.INTERNAL_FAILURE, observation.outcome());
        assertEquals(0, dns.calls);
        assertNull(transport.lastRequest);
    }

    private static SafeEventDeliveryEngine engine(
            DnsLookup dns, DeliveryTransport transport, LongSupplier clock) {
        return engine(
                dns,
                transport,
                clock,
                new HealthChangeEventJsonSerializer(new ObjectMapper()));
    }

    private static SafeEventDeliveryEngine engine(
            DnsLookup dns,
            DeliveryTransport transport,
            LongSupplier clock,
            Function<HealthChangeEventPayload, byte[]> serializer) {
        return new SafeEventDeliveryEngine(
                new ValidatedDeliveryEndpoint(
                        URI.create("https://events.example.com/api/v1/health-events"),
                        "events.example.com"),
                "delivery-token",
                DEFAULT_LIMITS,
                dns,
                new GlobalAddressPolicy(),
                transport,
                serializer,
                clock);
    }

    private static HealthChangeEventPayload event() {
        return new HealthChangeEventPayload(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new ResourceReference("role-resource-123"),
                new SourceRevision(42),
                Optional.of(UUID.fromString("00000000-0000-0000-0000-000000000003")),
                Health.DEGRADED,
                Health.BROKEN,
                Instant.parse("2026-08-02T01:02:03.456Z"));
    }

    private static InetAddress address(String value) throws Exception {
        return InetAddress.getByName(value);
    }

    private static Stream<Arguments> dnsFailures() {
        return Stream.of(
                Arguments.of(DnsLookupException.Reason.DNS_FAILURE, EventDeliveryOutcome.DNS_FAILURE),
                Arguments.of(
                        DnsLookupException.Reason.INTERNAL_FAILURE,
                        EventDeliveryOutcome.INTERNAL_FAILURE));
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
                        OutboundHttpFailure.Kind.CONNECT_TIMEOUT,
                        EventDeliveryOutcome.CONNECT_TIMEOUT),
                Arguments.of(OutboundHttpFailure.Kind.READ_TIMEOUT, EventDeliveryOutcome.READ_TIMEOUT),
                Arguments.of(OutboundHttpFailure.Kind.TLS_FAILURE, EventDeliveryOutcome.TLS_FAILURE),
                Arguments.of(
                        OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE,
                        EventDeliveryOutcome.RESPONSE_TOO_LARGE),
                Arguments.of(OutboundHttpFailure.Kind.NETWORK_FAILURE, EventDeliveryOutcome.NETWORK_FAILURE),
                Arguments.of(OutboundHttpFailure.Kind.INTERNAL_FAILURE, EventDeliveryOutcome.INTERNAL_FAILURE));
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

        private final int statusCode;
        private final List<ApprovedDeliveryRequest> requests = new ArrayList<>();
        private ApprovedDeliveryRequest lastRequest;

        private RecordingTransport(int statusCode) {
            this.statusCode = statusCode;
        }

        @Override
        public int execute(ApprovedDeliveryRequest request, Duration remainingTime) {
            requests.add(request);
            lastRequest = request;
            return statusCode;
        }
    }

    private static final class MutableClock implements LongSupplier {

        private long nanos;

        @Override
        public long getAsLong() {
            return nanos;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }
    }
}

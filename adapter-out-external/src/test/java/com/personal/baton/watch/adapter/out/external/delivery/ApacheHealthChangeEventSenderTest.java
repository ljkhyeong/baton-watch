package com.personal.baton.watch.adapter.out.external.delivery;

import static com.personal.baton.watch.adapter.out.external.delivery.EventDeliveryTestFixtures.DEFAULT_LIMITS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.adapter.out.external.check.DnsLookup;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryObservation;
import com.personal.baton.watch.application.monitoring.model.EventDeliveryOutcome;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import com.personal.baton.watch.domain.monitoring.Health;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import com.personal.baton.watch.domain.monitoring.SourceRevision;
import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class ApacheHealthChangeEventSenderTest {

    @Test
    void validatesTheEndpointBeforeCreatingAProductionSender() {
        assertThrows(
                IllegalArgumentException.class,
                () -> productionSender(
                        URI.create("http://events.example.com/callback"),
                        "0123456789abcdef0123456789abcdef"));
    }

    @Test
    void acceptsTheInclusiveBearerTokenLengthBoundariesAndUrlSafePunctuation() {
        try (ApacheHealthChangeEventSender ignored = productionSender(
                URI.create("https://events.example.com/callback"),
                "._~-" + "A".repeat(28))) {
            // 32자 하한과 허용 구두점을 함께 검증한다.
        }
        try (ApacheHealthChangeEventSender ignored = productionSender(
                URI.create("https://events.example.com/callback"),
                "A".repeat(200))) {
            // 200자 상한을 검증한다.
        }
    }

    @ParameterizedTest
    @MethodSource("rejectedBearerTokens")
    void rejectsBearerTokensOutsideTheUrlSafeSyntaxOrLength(String token) {
        assertThrows(
                IllegalArgumentException.class,
                () -> productionSender(URI.create("https://events.example.com/callback"), token));
    }

    @Test
    void resolvesEveryAttemptAndPassesOnlyApprovedPinnedAddressesToTheTransport() throws Exception {
        RecordingDnsLookup dns = new RecordingDnsLookup(List.of(
                address("8.8.8.8"), address("1.1.1.1")));
        RecordingTransport transport = new RecordingTransport(204);
        ApacheHealthChangeEventSender sender = sender(dns, transport, System::nanoTime);

        EventDeliveryObservation first = sender.send(event());
        EventDeliveryObservation second = sender.send(event());

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
        SentDelivery lastRequest = transport.requests.getLast();
        assertEquals(List.of(address("8.8.8.8"), address("1.1.1.1")), lastRequest.addresses());
        assertEquals("00000000-0000-0000-0000-000000000001", lastRequest.idempotencyKey());
        assertEquals(
                JsonMapper.shared().readTree(lastRequest.payload()).get("eventId").stringValue(),
                lastRequest.idempotencyKey());
    }

    @Test
    void rejectsTheEntireDnsAnswerWhenOneAddressIsNotPublicGlobal() throws Exception {
        RecordingDnsLookup dns = new RecordingDnsLookup(List.of(
                address("8.8.8.8"), address("127.0.0.1")));
        RecordingTransport transport = new RecordingTransport(204);

        EventDeliveryObservation observation = sender(dns, transport, System::nanoTime).send(event());

        assertEquals(EventDeliveryOutcome.DESTINATION_REJECTED, observation.outcome());
        assertNull(observation.httpStatusCode());
        assertTrue(transport.requests.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = OutboundHttpFailure.Kind.class, names = {"DNS_FAILURE", "INTERNAL_FAILURE"})
    void mapsDnsFailuresToBoundedOutcomes(OutboundHttpFailure.Kind kind) {
        DnsLookup dns = (hostname, timeout) -> {
            throw new OutboundHttpFailure(kind);
        };
        RecordingTransport transport = new RecordingTransport(204);

        EventDeliveryObservation observation = sender(dns, transport, System::nanoTime).send(event());

        assertEquals(EventDeliveryOutcome.valueOf(kind.name()), observation.outcome());
        assertNull(observation.httpStatusCode());
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void treatsRedirectAsClientErrorWithoutAnotherRequest() throws Exception {
        RecordingTransport transport = new RecordingTransport(302);

        EventDeliveryObservation observation = sender(transport).send(event());

        assertEquals(EventDeliveryOutcome.HTTP_CLIENT_ERROR, observation.outcome());
        assertEquals(302, observation.httpStatusCode());
        assertEquals(1, transport.requests.size());
    }

    @Test
    void mapsUnsupportedFinalHttpMetadataToNetworkFailure() throws Exception {
        RecordingTransport transport = new RecordingTransport(101);

        EventDeliveryObservation observation = sender(transport).send(event());

        assertEquals(EventDeliveryOutcome.NETWORK_FAILURE, observation.outcome());
        assertNull(observation.httpStatusCode());
    }

    @Test
    void keepsTheServerRetryTimeInTheDeliveryObservation() throws Exception {
        Instant retryAt = Instant.parse("2026-08-01T00:02:00Z");
        DeliveryTransport transport = (addresses, payload, key, remaining) -> new DeliveryResponse(503, retryAt);

        EventDeliveryObservation observation = sender(transport).send(event());

        assertEquals(EventDeliveryObservation.forHttpStatus(503, retryAt), observation);
    }

    @ParameterizedTest
    @EnumSource(OutboundHttpFailure.Kind.class)
    void mapsTransportFailuresToBoundedOutcomes(OutboundHttpFailure.Kind kind) throws Exception {
        DeliveryTransport transport = (addresses, payload, key, remaining) -> {
            throw new OutboundHttpFailure(kind);
        };

        EventDeliveryObservation observation = sender(transport).send(event());

        assertEquals(EventDeliveryOutcome.valueOf(kind.name()), observation.outcome());
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

        EventDeliveryObservation observation = sender(dns, transport, clock).send(event());

        assertEquals(EventDeliveryOutcome.DNS_FAILURE, observation.outcome());
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void serializationFailureStopsBeforeDnsOrTransport() throws Exception {
        RecordingDnsLookup dns = new RecordingDnsLookup(List.of(address("8.8.8.8")));
        RecordingTransport transport = new RecordingTransport(204);
        ApacheHealthChangeEventSender sender = sender(
                dns,
                transport,
                System::nanoTime,
                payload -> {
                    throw new IllegalStateException("sensitive serialization detail");
                });

        EventDeliveryObservation observation = sender.send(event());

        assertEquals(EventDeliveryOutcome.INTERNAL_FAILURE, observation.outcome());
        assertEquals(0, dns.calls);
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void serializationIsIncludedInTheTotalDeadline() throws Exception {
        MutableClock clock = new MutableClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(List.of(address("8.8.8.8")));
        RecordingTransport transport = new RecordingTransport(204);
        ApacheHealthChangeEventSender sender = sender(
                dns,
                transport,
                clock,
                payload -> {
                    clock.advance(DEFAULT_LIMITS.totalTimeout());
                    return new byte[] {1};
                });

        EventDeliveryObservation observation = sender.send(event());

        assertEquals(EventDeliveryOutcome.CONNECT_TIMEOUT, observation.outcome());
        assertEquals(0, dns.calls);
        assertTrue(transport.requests.isEmpty());
    }

    private static ApacheHealthChangeEventSender productionSender(URI endpoint, String bearerToken) {
        return new ApacheHealthChangeEventSender(
                endpoint, bearerToken, DEFAULT_LIMITS, 2, 8, 1, 1, Clock.systemUTC());
    }

    private static Stream<String> rejectedBearerTokens() {
        String prefix = "A".repeat(31);
        return Stream.of(
                prefix, prefix + "+", prefix + "/", prefix + " ", prefix + "é", "A".repeat(201));
    }

    private static ApacheHealthChangeEventSender sender(DeliveryTransport transport) throws Exception {
        return sender(new RecordingDnsLookup(List.of(address("8.8.8.8"))), transport, System::nanoTime);
    }

    private static ApacheHealthChangeEventSender sender(
            DnsLookup dns, DeliveryTransport transport, LongSupplier clock) {
        return sender(dns, transport, clock, HealthChangeEventRequest::serialize);
    }

    private static ApacheHealthChangeEventSender sender(
            DnsLookup dns,
            DeliveryTransport transport,
            LongSupplier clock,
            Function<HealthChangeEventPayload, byte[]> serializer) {
        return new ApacheHealthChangeEventSender(
                "events.example.com", DEFAULT_LIMITS, serializer, dns, transport, clock);
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
        private final List<SentDelivery> requests = new ArrayList<>();

        private RecordingTransport(int statusCode) {
            this.statusCode = statusCode;
        }

        @Override
        public DeliveryResponse execute(
                List<InetAddress> addresses, byte[] payload, String idempotencyKey, Duration remainingTime) {
            requests.add(new SentDelivery(addresses, payload, idempotencyKey));
            return new DeliveryResponse(statusCode, null);
        }
    }

    private record SentDelivery(List<InetAddress> addresses, byte[] payload, String idempotencyKey) {}

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

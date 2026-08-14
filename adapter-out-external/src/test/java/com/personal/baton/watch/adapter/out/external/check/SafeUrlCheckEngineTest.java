package com.personal.baton.watch.adapter.out.external.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.domain.monitoring.CheckOutcome;
import com.personal.baton.watch.domain.monitoring.TargetUrl;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SafeUrlCheckEngineTest {

    private static final String PUBLIC_V4 = "8.8.8.8";
    private static final CheckerLimits DEFAULT_LIMITS = new CheckerLimits(
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            64L * 1024L,
            3,
            100,
            8 * 1024);

    @Test
    void followsRelativeRedirectsAndRevalidatesRepinsEveryHop() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ofMillis(5));
        transport.add(redirect(302, "/next", 5));
        transport.add(finalStatus(204, 7));
        SafeUrlCheckEngine engine = engine(DEFAULT_LIMITS, dns, transport, clock);

        CheckObservation observation = engine.check(new TargetUrl("https://Example.COM/start"));

        assertEquals(CheckOutcome.SUCCESS, observation.outcome());
        assertEquals(204, observation.httpStatusCode());
        assertEquals(Duration.ofMillis(10), observation.duration());
        assertEquals(12, observation.responseBytes());
        assertEquals(1, observation.redirectCount());
        assertEquals(List.of("Example.COM", "Example.COM"), dns.hostnames);
        assertEquals(2, transport.targets.size());
        assertEquals("https://Example.COM/start", transport.targets.get(0).target().uri().toString());
        assertEquals("https://Example.COM/next", transport.targets.get(1).target().uri().toString());
        assertEquals(publicAnswer(), transport.targets.get(0).addresses());
        assertEquals(publicAnswer(), transport.targets.get(1).addresses());
        assertEquals(
                List.of(DEFAULT_LIMITS.maxResponseBytes(), DEFAULT_LIMITS.maxResponseBytes() - 5),
                transport.remainingByteBudgets);
    }

    @Test
    void rejectsAMixedDnsAnswerAfterRedirectBeforeASecondConnection() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        dns.answers.put("blocked.example", List.of(
                InetAddress.getByName(PUBLIC_V4), InetAddress.getByName("10.0.0.1")));
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
        transport.add(redirect(301, "https://blocked.example/", 2));

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://public.example/"));

        assertEquals(CheckOutcome.DESTINATION_REJECTED, observation.outcome());
        assertEquals(2, observation.responseBytes());
        assertEquals(1, observation.redirectCount());
        assertEquals(List.of("public.example", "blocked.example"), dns.hostnames);
        assertEquals(1, transport.targets.size());
    }

    @Test
    void rejectsReservedIpv6BeforeTheInitialConnection() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(List.of(InetAddress.getByName("3000::1")));
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://reserved.example/"));

        assertEquals(CheckOutcome.DESTINATION_REJECTED, observation.outcome());
        assertEquals(List.of("reserved.example"), dns.hostnames);
        assertEquals(0, transport.targets.size());
    }

    @Test
    void rejectsAzureWireServerBeforeTheInitialConnection() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(List.of(InetAddress.getByName("168.63.129.16")));
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://platform-service.example/"));

        assertEquals(CheckOutcome.DESTINATION_REJECTED, observation.outcome());
        assertEquals(List.of("platform-service.example"), dns.hostnames);
        assertEquals(0, transport.targets.size());
    }

    @Test
    void rejectsHttpsDowngradeWithoutResolvingOrConnectingToTheRedirect() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
        transport.add(redirect(302, "http://other.example/", 0));

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://start.example/"));

        assertEquals(CheckOutcome.REDIRECT_REJECTED, observation.outcome());
        assertEquals(0, observation.redirectCount());
        assertEquals(List.of("start.example"), dns.hostnames);
        assertEquals(1, transport.targets.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/%0d%0aHost:internal",
        "/%5c%5cevil.example",
        "%0d/../safe",
        "%5c/../safe"
    })
    void rejectsEncodedControlOrBackslashRedirectsBeforeASecondConnection(String location) throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
        transport.add(redirect(302, location, 0));

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://start.example/"));

        assertEquals(CheckOutcome.REDIRECT_REJECTED, observation.outcome());
        assertEquals(0, observation.redirectCount());
        assertEquals(List.of("start.example"), dns.hostnames);
        assertEquals(1, transport.targets.size());
    }

    @Test
    void rejectsAHistoricalUnsafeTargetBeforeDnsOrConnection() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://example.com/%0d%0aHost:internal"));

        assertEquals(CheckOutcome.DESTINATION_REJECTED, observation.outcome());
        assertEquals(List.of(), dns.hostnames);
        assertEquals(0, transport.targets.size());
    }

    @Test
    void rejectsRedirectLoopUsingCanonicalHostAndDefaultPort() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
        transport.add(redirect(308, "https://EXAMPLE.com:443/", 1));

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://example.com"));

        assertEquals(CheckOutcome.REDIRECT_REJECTED, observation.outcome());
        assertEquals(0, observation.redirectCount());
        assertEquals(1, transport.targets.size());
    }

    @Test
    void stopsAfterThreeFollowedRedirects() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
        transport.add(redirect(301, "https://one.example/", 1));
        transport.add(redirect(302, "https://two.example/", 1));
        transport.add(redirect(303, "https://three.example/", 1));
        transport.add(redirect(307, "https://four.example/", 1));

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://start.example/"));

        assertEquals(CheckOutcome.TOO_MANY_REDIRECTS, observation.outcome());
        assertEquals(3, observation.redirectCount());
        assertEquals(4, observation.responseBytes());
        assertEquals(4, dns.hostnames.size());
        assertEquals(4, transport.targets.size());
    }

    @Test
    void rejectsMissingAndMultipleLocationHeaders() throws Exception {
        for (HttpHopResponse response : List.of(
                new HttpHopResponse(302, List.of(), 0),
                new HttpHopResponse(302, List.of("/one", "/two"), 0))) {
            MutableNanoClock clock = new MutableNanoClock();
            RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
            ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
            transport.add(response);

            CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                    .check(new TargetUrl("https://start.example/"));

            assertEquals(CheckOutcome.REDIRECT_REJECTED, observation.outcome());
        }
    }

    @ParameterizedTest
    @MethodSource("finalStatuses")
    void mapsOnlyBoundedFinalHttpStatusMetadata(int status, CheckOutcome expected) throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
        transport.add(finalStatus(status, 4));

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://status.example/"));

        assertEquals(expected, observation.outcome());
        assertEquals(status >= 200 ? status : null, observation.httpStatusCode());
        assertEquals(4, observation.responseBytes());
    }

    @ParameterizedTest
    @MethodSource("transportFailures")
    void mapsTransportFailuresWithoutExceptionDetails(
            OutboundHttpFailure.Kind transportKind, CheckOutcome expected) throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
        OutboundHttpFailure scriptedFailure = new OutboundHttpFailure(transportKind, 3);
        transport.add(scriptedFailure);

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://failure.example/secret?token=value"));

        assertEquals(expected, observation.outcome());
        assertEquals(3, observation.responseBytes());
        assertEquals(null, observation.httpStatusCode());
        assertSame(scriptedFailure, transport.lastFailure);
    }

    @Test
    void mapsDnsFailureWithoutCallingTheTransport() {
        MutableNanoClock clock = new MutableNanoClock();
        DnsLookup dns = (hostname, timeout) -> {
            throw new DnsLookupException(DnsLookupException.Reason.DNS_FAILURE);
        };
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://missing.example/"));

        assertEquals(CheckOutcome.DNS_FAILURE, observation.outcome());
        assertEquals(0, transport.targets.size());
    }

    @Test
    void mapsResolverInfrastructureFailuresToInternalFailure() {
        MutableNanoClock clock = new MutableNanoClock();
        DnsLookup dns = (hostname, timeout) -> {
            throw new DnsLookupException(DnsLookupException.Reason.INTERNAL_FAILURE);
        };
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://resolver-capacity.example/"));

        assertEquals(CheckOutcome.INTERNAL_FAILURE, observation.outcome());
        assertEquals(0, transport.targets.size());
    }

    @Test
    void enforcesOneCumulativeByteCapAcrossRedirectResponses() throws Exception {
        CheckerLimits limits = new CheckerLimits(
                Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(5), 10, 3, 100, 8192);
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
        transport.add(redirect(302, "/next", 6));
        transport.add(new OutboundHttpFailure(OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE, 4));

        CheckObservation observation = engine(limits, dns, transport, clock)
                .check(new TargetUrl("https://bytes.example/start"));

        assertEquals(CheckOutcome.RESPONSE_TOO_LARGE, observation.outcome());
        assertEquals(10, observation.responseBytes());
        assertEquals(1, observation.redirectCount());
        assertEquals(List.of(10L, 4L), transport.remainingByteBudgets);
    }

    @Test
    void allowsABodylessFinalResponseAfterRedirectsUseTheExactByteBudget() throws Exception {
        CheckerLimits limits = new CheckerLimits(
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                10,
                3,
                100,
                8_192);
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
        transport.add(redirect(302, "/final", 10));
        transport.add(finalStatus(204, 0));

        CheckObservation observation = engine(limits, dns, transport, clock)
                .check(new TargetUrl("https://bytes.example/start"));

        assertEquals(CheckOutcome.SUCCESS, observation.outcome());
        assertEquals(10, observation.responseBytes());
        assertEquals(1, observation.redirectCount());
        assertEquals(List.of(10L, 0L), transport.remainingByteBudgets);
    }

    @Test
    void totalDeadlineIncludesDnsResolution() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        List<InetAddress> answer = publicAnswer();
        DnsLookup dns = (hostname, timeout) -> {
            clock.advance(Duration.ofSeconds(6));
            return answer;
        };
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://slow-dns.example/"));

        assertEquals(CheckOutcome.DNS_FAILURE, observation.outcome());
        assertEquals(Duration.ofSeconds(6), observation.duration());
        assertEquals(0, transport.targets.size());
    }

    @Test
    void unexpectedAdapterRuntimeFailureBecomesInternalFailureMetadata() {
        MutableNanoClock clock = new MutableNanoClock();
        DnsLookup dns = (hostname, timeout) -> {
            throw new IllegalStateException("detail that must not escape");
        };
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://internal.example/"));

        assertEquals(CheckOutcome.INTERNAL_FAILURE, observation.outcome());
        assertEquals(null, observation.httpStatusCode());
    }

    @Test
    void transportIllegalArgumentFailureRemainsAnInternalFailure() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        HttpHopTransport transport = (target, remainingTime, remainingBytes) -> {
            throw new IllegalArgumentException("detail that must not escape");
        };

        CheckObservation observation = engine(
                        DEFAULT_LIMITS,
                        new RecordingDnsLookup(publicAnswer()),
                        transport,
                        clock)
                .check(new TargetUrl("https://internal.example/"));

        assertEquals(CheckOutcome.INTERNAL_FAILURE, observation.outcome());
        assertEquals(null, observation.httpStatusCode());
    }

    private static SafeUrlCheckEngine engine(
            CheckerLimits limits,
            DnsLookup dns,
            HttpHopTransport transport,
            LongSupplier clock) {
        return new SafeUrlCheckEngine(
                limits,
                new TargetUriPolicy(),
                dns,
                new GlobalAddressPolicy(),
                transport,
                clock);
    }

    private static List<InetAddress> publicAnswer() throws Exception {
        return List.of(InetAddress.getByName(PUBLIC_V4));
    }

    private static HttpHopResponse finalStatus(int statusCode, long responseBytes) {
        return new HttpHopResponse(statusCode, List.of(), responseBytes);
    }

    private static HttpHopResponse redirect(int statusCode, String location, long responseBytes) {
        return new HttpHopResponse(statusCode, List.of(location), responseBytes);
    }

    private static java.util.stream.Stream<Arguments> finalStatuses() {
        return java.util.stream.Stream.of(
                Arguments.of(204, CheckOutcome.SUCCESS),
                Arguments.of(304, CheckOutcome.SUCCESS),
                Arguments.of(404, CheckOutcome.HTTP_CLIENT_ERROR),
                Arguments.of(503, CheckOutcome.HTTP_SERVER_ERROR),
                Arguments.of(199, CheckOutcome.NETWORK_FAILURE));
    }

    private static java.util.stream.Stream<Arguments> transportFailures() {
        return java.util.stream.Stream.of(
                Arguments.of(OutboundHttpFailure.Kind.CONNECT_TIMEOUT, CheckOutcome.CONNECT_TIMEOUT),
                Arguments.of(OutboundHttpFailure.Kind.READ_TIMEOUT, CheckOutcome.READ_TIMEOUT),
                Arguments.of(OutboundHttpFailure.Kind.TLS_FAILURE, CheckOutcome.TLS_FAILURE),
                Arguments.of(OutboundHttpFailure.Kind.RESPONSE_TOO_LARGE, CheckOutcome.RESPONSE_TOO_LARGE),
                Arguments.of(OutboundHttpFailure.Kind.NETWORK_FAILURE, CheckOutcome.NETWORK_FAILURE),
                Arguments.of(OutboundHttpFailure.Kind.INTERNAL_FAILURE, CheckOutcome.INTERNAL_FAILURE));
    }

    private static final class MutableNanoClock implements LongSupplier {

        private long now;

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(Duration duration) {
            now += duration.toNanos();
        }
    }

    private static final class RecordingDnsLookup implements DnsLookup {

        private final List<String> hostnames = new ArrayList<>();
        private final Map<String, List<InetAddress>> answers = new HashMap<>();
        private final List<InetAddress> defaultAnswer;

        private RecordingDnsLookup(List<InetAddress> defaultAnswer) {
            this.defaultAnswer = defaultAnswer;
        }

        @Override
        public List<InetAddress> resolve(String hostname, Duration timeout) {
            hostnames.add(hostname);
            return answers.getOrDefault(hostname, defaultAnswer);
        }
    }

    private static final class ScriptedTransport implements HttpHopTransport {

        private final Deque<Object> script = new ArrayDeque<>();
        private final List<ApprovedTarget> targets = new ArrayList<>();
        private final List<Long> remainingByteBudgets = new ArrayList<>();
        private final MutableNanoClock clock;
        private final Duration timePerHop;
        private OutboundHttpFailure lastFailure;

        private ScriptedTransport(MutableNanoClock clock, Duration timePerHop) {
            this.clock = clock;
            this.timePerHop = timePerHop;
        }

        void add(Object result) {
            script.addLast(result);
        }

        @Override
        public HttpHopResponse execute(ApprovedTarget target, Duration remainingTime, long remainingBytes)
                throws OutboundHttpFailure {
            targets.add(target);
            remainingByteBudgets.add(remainingBytes);
            clock.advance(timePerHop);
            Object next = script.removeFirst();
            if (next instanceof OutboundHttpFailure failure) {
                lastFailure = failure;
                throw failure;
            }
            return (HttpHopResponse) next;
        }
    }
}

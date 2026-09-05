package com.personal.baton.watch.adapter.out.external.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
            3,
            100,
            8 * 1024);

    @Test
    void followsRelativeRedirectsAndRevalidatesRepinsEveryHop() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ofMillis(5));
        transport.add(redirect(302, "/next"));
        transport.add(finalStatus(204));
        SafeUrlCheckEngine engine = engine(DEFAULT_LIMITS, dns, transport, clock);

        CheckObservation observation = engine.check(new TargetUrl("https://Example.COM/start"));

        assertEquals(CheckOutcome.SUCCESS, observation.outcome());
        assertEquals(204, observation.httpStatusCode());
        assertEquals(Duration.ofMillis(10), observation.duration());
        assertEquals(0, observation.responseBytes());
        assertEquals(1, observation.redirectCount());
        assertEquals(List.of("Example.COM", "Example.COM"), dns.hostnames);
        assertEquals(2, transport.targets.size());
        assertEquals("https://Example.COM/start", transport.targets.get(0).target().uri().toString());
        assertEquals("https://Example.COM/next", transport.targets.get(1).target().uri().toString());
        assertEquals(publicAnswer(), transport.targets.get(0).addresses());
        assertEquals(publicAnswer(), transport.targets.get(1).addresses());
    }

    @Test
    void rejectsAMixedDnsAnswerAfterRedirectBeforeASecondConnection() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        dns.answers.put("blocked.example", List.of(
                InetAddress.getByName(PUBLIC_V4), InetAddress.getByName("10.0.0.1")));
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
        transport.add(redirect(301, "https://blocked.example/"));

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://public.example/"));

        assertEquals(CheckOutcome.DESTINATION_REJECTED, observation.outcome());
        assertEquals(0, observation.responseBytes());
        assertEquals(1, observation.redirectCount());
        assertEquals(List.of("public.example", "blocked.example"), dns.hostnames);
        assertEquals(1, transport.targets.size());
    }

    @Test
    void rejectsHttpsDowngradeWithoutResolvingOrConnectingToTheRedirect() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
        transport.add(redirect(302, "http://other.example/"));

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
        transport.add(redirect(302, location));

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
        transport.add(redirect(308, "https://EXAMPLE.com:443/"));

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
        transport.add(redirect(301, "https://one.example/"));
        transport.add(redirect(302, "https://two.example/"));
        transport.add(redirect(303, "https://three.example/"));
        transport.add(redirect(307, "https://four.example/"));

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://start.example/"));

        assertEquals(CheckOutcome.TOO_MANY_REDIRECTS, observation.outcome());
        assertEquals(3, observation.redirectCount());
        assertEquals(0, observation.responseBytes());
        assertEquals(4, dns.hostnames.size());
        assertEquals(4, transport.targets.size());
    }

    @Test
    void rejectsMissingAndMultipleLocationHeaders() throws Exception {
        for (HttpHopResponse response : List.of(
                new HttpHopResponse(302, List.of()),
                new HttpHopResponse(302, List.of("/one", "/two")))) {
            MutableNanoClock clock = new MutableNanoClock();
            RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
            ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
            transport.add(response);

            CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                    .check(new TargetUrl("https://start.example/"));

            assertEquals(CheckOutcome.REDIRECT_REJECTED, observation.outcome());
        }
    }

    @Test
    void mapsUnsupportedFinalHttpStatusToNetworkFailureWithoutStatusMetadata() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
        transport.add(finalStatus(199));

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://status.example/"));

        assertEquals(CheckOutcome.NETWORK_FAILURE, observation.outcome());
        assertNull(observation.httpStatusCode());
        assertEquals(0, observation.responseBytes());
    }

    @ParameterizedTest
    @MethodSource("transportFailures")
    void mapsTransportFailuresWithoutExceptionDetails(
            OutboundHttpFailure.Kind transportKind, CheckOutcome expected) throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        RecordingDnsLookup dns = new RecordingDnsLookup(publicAnswer());
        ScriptedTransport transport = new ScriptedTransport(clock, Duration.ZERO);
        OutboundHttpFailure scriptedFailure = new OutboundHttpFailure(transportKind);
        transport.add(scriptedFailure);

        CheckObservation observation = engine(DEFAULT_LIMITS, dns, transport, clock)
                .check(new TargetUrl("https://failure.example/secret?token=value"));

        assertEquals(expected, observation.outcome());
        assertEquals(0, observation.responseBytes());
        assertNull(observation.httpStatusCode());
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
    void transportIllegalArgumentFailureRemainsAnInternalFailure() throws Exception {
        MutableNanoClock clock = new MutableNanoClock();
        HttpHopTransport transport = (target, remainingTime) -> {
            throw new IllegalArgumentException("detail that must not escape");
        };

        CheckObservation observation = engine(
                        DEFAULT_LIMITS,
                        new RecordingDnsLookup(publicAnswer()),
                        transport,
                        clock)
                .check(new TargetUrl("https://internal.example/"));

        assertEquals(CheckOutcome.INTERNAL_FAILURE, observation.outcome());
        assertNull(observation.httpStatusCode());
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

    private static HttpHopResponse finalStatus(int statusCode) {
        return new HttpHopResponse(statusCode, List.of());
    }

    private static HttpHopResponse redirect(int statusCode, String location) {
        return new HttpHopResponse(statusCode, List.of(location));
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
        private final MutableNanoClock clock;
        private final Duration timePerHop;

        private ScriptedTransport(MutableNanoClock clock, Duration timePerHop) {
            this.clock = clock;
            this.timePerHop = timePerHop;
        }

        void add(Object result) {
            script.addLast(result);
        }

        @Override
        public HttpHopResponse execute(ApprovedTarget target, Duration remainingTime)
                throws OutboundHttpFailure {
            targets.add(target);
            clock.advance(timePerHop);
            Object next = script.removeFirst();
            if (next instanceof OutboundHttpFailure failure) {
                throw failure;
            }
            return (HttpHopResponse) next;
        }
    }
}

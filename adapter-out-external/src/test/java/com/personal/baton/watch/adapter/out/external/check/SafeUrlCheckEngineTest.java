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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
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

    private final MutableNanoClock clock = new MutableNanoClock();
    private final ScriptedTransport transport = new ScriptedTransport(clock);
    private RecordingDnsLookup dns;

    @BeforeEach
    void setUp() throws Exception {
        dns = new RecordingDnsLookup(publicAnswer());
    }

    @Test
    void followsRelativeRedirectsAndRevalidatesRepinsEveryHop() throws Exception {
        transport.timePerHop = Duration.ofMillis(5);
        transport.add(redirect(302, "/next"));
        transport.add(finalStatus(204));

        CheckObservation observation = engine().check(new TargetUrl("https://Example.COM/start"));

        assertEquals(CheckOutcome.SUCCESS, observation.outcome());
        assertEquals(204, observation.httpStatusCode());
        assertEquals(Duration.ofMillis(10), observation.duration());
        assertEquals(1, observation.redirectCount());
        assertEquals(List.of("Example.COM", "Example.COM"), dns.hostnames);
        assertEquals(2, transport.targets.size());
        assertEquals("https://Example.COM/start", transport.targets.get(0).target().uri().toString());
        assertEquals("https://Example.COM/next", transport.targets.get(1).target().uri().toString());
        assertEquals(publicAnswer(), transport.targets.get(0).addresses());
        assertEquals(publicAnswer(), transport.targets.get(1).addresses());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            https://Example.COM/docs/page?old=1 | ?page=2 | https://Example.COM/docs/page?page=2
            https://Example.COM/docs/page | ?page=2 | https://Example.COM/docs/page?page=2
            https://Example.COM/docs/a%2Fb?old=%2F | ?next=%2f%3F&tag=a+b | https://Example.COM/docs/a%2Fb?next=%2f%3F&tag=a+b
            https://Example.COM/docs/page?old=1 | ? | https://Example.COM/docs/page?
            https://Example.COM/문서?old=1 | ?page=2 | https://Example.COM/문서?page=2
            https://Example.COM/docs | ?q=값 | https://Example.COM/docs?q=값
            https://Example.COM/docs//page | /docs/page | https://Example.COM/docs/page
            https://Example.COM/docs/page | /docs//page | https://Example.COM/docs//page
            https://Example.COM/docs//page | next | https://Example.COM/docs//next
            https://Example.COM/docs//page | ./next | https://Example.COM/docs//next
            https://Example.COM/docs//page | ../next | https://Example.COM/docs/next
            https://Example.COM/docs//page | a//next | https://Example.COM/docs//a//next
            https://Example.COM/docs//page | a/..//next | https://Example.COM/docs///next
            https://Example.COM/docs//page | . | https://Example.COM/docs//
            https://Example.COM/docs//page | .. | https://Example.COM/docs/
            https://Example.COM/docs/page | ../../../next | https://Example.COM/next
            https://Example.COM | next | https://Example.COM/next
            https://Example.COM//docs/page | ../next | https://Example.COM//next
            https://Example.COM/docs//page | a//%2e%2E/x%2Fy?next=%2f%3F&tag=a+b | https://Example.COM/docs//a//%2e%2E/x%2Fy?next=%2f%3F&tag=a+b
            https://Example.COM/start | /docs/../guide | https://Example.COM/guide
            https://Example.COM/start | /./guide | https://Example.COM/guide
            https://Example.COM/start | /../../guide | https://Example.COM/guide
            https://Example.COM/start | /docs/. | https://Example.COM/docs/
            https://Example.COM/start | https://Example.COM/docs/../guide | https://Example.COM/guide
            https://Example.COM/start | //Example.COM/docs/../guide | https://Example.COM/guide
            https://Example.COM/start | /docs//./a/../%2e%2E/x%2Fy?next=/../&tag=%2f | https://Example.COM/docs//%2e%2E/x%2Fy?next=/../&tag=%2f
            """)
    void resolvesRedirectsWithoutChangingPathOrQueryEncoding(String target, String location, String expected)
            throws Exception {
        transport.add(redirect(302, location));
        transport.add(finalStatus(200));

        CheckObservation observation = engine().check(new TargetUrl(target));

        assertEquals(CheckOutcome.SUCCESS, observation.outcome());
        assertEquals(1, observation.redirectCount());
        assertEquals(expected, transport.targets.get(1).target().uri().toString());
        assertEquals(List.of("Example.COM", "Example.COM"), dns.hostnames);
        assertEquals(publicAnswer(), transport.targets.get(1).addresses());
    }

    @ParameterizedTest
    @CsvSource({
        "https://example.com/docs/page?page=2, ?page=2, 302",
        "https://example.com/문서?page=2, ?page=2, 302",
        "https://example.com/docs//page, page, 302",
        "https://example.com/docs//page, ./page, 302",
        "https://example.com/docs//page, next/../page, 302",
        "https://example.com/docs/page, /docs/./page, 302",
        "https://example.com/docs/page, https://example.com/docs/other/../page, 302",
        "https://example.com/docs/page, //example.com/docs/other/../page, 302",
        "https://example.com, https://EXAMPLE.com:443/, 308",
        "https://example.com/docs//page, https://EXAMPLE.com:443/docs//page, 308"
    })
    void rejectsARedirectToTheSamePageBeforeAnotherConnection(String target, String location, int status)
            throws Exception {
        transport.add(redirect(status, location));
        transport.add(finalStatus(200));

        CheckObservation observation = engine().check(new TargetUrl(target));

        assertEquals(CheckOutcome.REDIRECT_REJECTED, observation.outcome());
        assertEquals(0, observation.redirectCount());
        assertEquals(List.of("example.com"), dns.hostnames);
        assertEquals(1, transport.targets.size());
    }

    @Test
    void rejectsAMixedDnsAnswerAfterRedirectBeforeASecondConnection() throws Exception {
        dns.answers.put("blocked.example", List.of(
                InetAddress.getByName(PUBLIC_V4), InetAddress.getByName("10.0.0.1")));
        transport.add(redirect(301, "https://blocked.example/"));

        CheckObservation observation = engine().check(new TargetUrl("https://public.example/"));

        assertEquals(CheckOutcome.DESTINATION_REJECTED, observation.outcome());
        assertEquals(1, observation.redirectCount());
        assertEquals(List.of("public.example", "blocked.example"), dns.hostnames);
        assertEquals(1, transport.targets.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/%0d%0aHost:internal",
        "/%5c%5cevil.example",
        "%0d/../safe",
        "%5c/../safe",
        "?next=%0d%0aHost:internal",
        "?next=%5c%5cevil.example",
        "/\uD800",
        "?query=\uDC00",
        "https:guide",
        "https:/docs/../guide",
        "ftp://example.com/docs/../guide",
        "https://user:password@example.com/docs/../guide",
        "/docs/../guide#section",
        "http://other.example/"
    })
    void rejectsUnsafeRedirectsBeforeASecondConnection(String location) throws Exception {
        transport.add(redirect(302, location));

        CheckObservation observation = engine().check(new TargetUrl("https://start.example/"));

        assertEquals(CheckOutcome.REDIRECT_REJECTED, observation.outcome());
        assertEquals(0, observation.redirectCount());
        assertEquals(List.of("start.example"), dns.hostnames);
        assertEquals(1, transport.targets.size());
    }

    @Test
    void rejectsAHistoricalUnsafeTargetBeforeDnsOrConnection() throws Exception {

        CheckObservation observation = engine().check(new TargetUrl("https://example.com/%0d%0aHost:internal"));

        assertEquals(CheckOutcome.DESTINATION_REJECTED, observation.outcome());
        assertEquals(List.of(), dns.hostnames);
        assertEquals(0, transport.targets.size());
    }

    @Test
    void stopsAfterThreeFollowedRedirects() throws Exception {
        transport.add(redirect(301, "https://one.example/"));
        transport.add(redirect(302, "https://two.example/"));
        transport.add(redirect(303, "https://three.example/"));
        transport.add(redirect(307, "https://four.example/"));

        CheckObservation observation = engine().check(new TargetUrl("https://start.example/"));

        assertEquals(CheckOutcome.TOO_MANY_REDIRECTS, observation.outcome());
        assertEquals(3, observation.redirectCount());
        assertEquals(4, dns.hostnames.size());
        assertEquals(4, transport.targets.size());
    }

    @Test
    void rejectsMissingAndMultipleLocationHeaders() throws Exception {
        for (HttpHopResponse response : List.of(
                new HttpHopResponse(302, List.of()),
                new HttpHopResponse(302, List.of("/one", "/two")))) {
            transport.add(response);

            CheckObservation observation = engine().check(new TargetUrl("https://start.example/"));

            assertEquals(CheckOutcome.REDIRECT_REJECTED, observation.outcome());
        }
    }

    @Test
    void mapsUnsupportedFinalHttpStatusToNetworkFailureWithoutStatusMetadata() throws Exception {
        transport.add(finalStatus(199));

        CheckObservation observation = engine().check(new TargetUrl("https://status.example/"));

        assertEquals(CheckOutcome.NETWORK_FAILURE, observation.outcome());
        assertNull(observation.httpStatusCode());
    }

    @ParameterizedTest
    @MethodSource("transportFailures")
    void mapsTransportFailuresWithoutExceptionDetails(
            OutboundHttpFailure.Kind transportKind, CheckOutcome expected) throws Exception {
        transport.timePerHop = Duration.ofMillis(5);
        OutboundHttpFailure scriptedFailure = new OutboundHttpFailure(transportKind);
        transport.add(redirect(302, "/next"));
        transport.add(scriptedFailure);

        CheckObservation observation = engine().check(new TargetUrl("https://failure.example/secret?token=value"));

        assertEquals(expected, observation.outcome());
        assertEquals(Duration.ofMillis(10), observation.duration());
        assertEquals(1, observation.redirectCount());
        assertNull(observation.httpStatusCode());
    }

    @ParameterizedTest
    @CsvSource({"DNS_FAILURE, DNS_FAILURE", "INTERNAL_FAILURE, INTERNAL_FAILURE"})
    void mapsDnsFailuresWithoutCallingTheTransport(DnsLookupException.Reason reason, CheckOutcome expected) {
        DnsLookup failingDns = (hostname, timeout) -> {
            throw new DnsLookupException(reason);
        };

        CheckObservation observation = engine(failingDns, transport)
                .check(new TargetUrl("https://missing.example/"));

        assertEquals(expected, observation.outcome());
        assertEquals(0, transport.targets.size());
    }

    @Test
    void totalDeadlineIncludesDnsResolution() throws Exception {
        List<InetAddress> answer = publicAnswer();
        DnsLookup slowDns = (hostname, timeout) -> {
            clock.advance(Duration.ofSeconds(6));
            return answer;
        };

        CheckObservation observation = engine(slowDns, transport)
                .check(new TargetUrl("https://slow-dns.example/"));

        assertEquals(CheckOutcome.DNS_FAILURE, observation.outcome());
        assertEquals(Duration.ofSeconds(6), observation.duration());
        assertEquals(0, transport.targets.size());
    }

    @Test
    void transportIllegalArgumentFailureRemainsAnInternalFailure() throws Exception {
        HttpHopTransport failingTransport = (target, remainingTime) -> {
            throw new IllegalArgumentException("detail that must not escape");
        };

        CheckObservation observation = engine(dns, failingTransport)
                .check(new TargetUrl("https://internal.example/"));

        assertEquals(CheckOutcome.INTERNAL_FAILURE, observation.outcome());
        assertNull(observation.httpStatusCode());
    }

    private SafeUrlCheckEngine engine() {
        return engine(dns, transport);
    }

    private SafeUrlCheckEngine engine(DnsLookup dnsLookup, HttpHopTransport hopTransport) {
        return new SafeUrlCheckEngine(
                DEFAULT_LIMITS,
                new TargetUriPolicy(),
                dnsLookup,
                new GlobalAddressPolicy(),
                hopTransport,
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
        private Duration timePerHop = Duration.ZERO;

        private ScriptedTransport(MutableNanoClock clock) {
            this.clock = clock;
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

package com.personal.baton.watch.adapter.out.external.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BoundedDnsLookupTest {

    @Test
    void rejectsExecutorBoundsBeforeAllocatingThreadsOrQueues() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedDnsLookup(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundedDnsLookup(1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BoundedDnsLookup(OutboundResourceBounds.MAX_DNS_THREADS + 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BoundedDnsLookup(
                        1, OutboundResourceBounds.MAX_DNS_QUEUE_CAPACITY + 1));
    }

    @Test
    void boundsAPlatformLookupAndDoesNotExposeTheHostnameInItsFailure() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (var lookup = new BoundedDnsLookup(1, 1, hostname -> {
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new UnknownHostException();
            }
            return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
        })) {
            OutboundHttpFailure failure = assertThrows(
                    OutboundHttpFailure.class,
                    () -> lookup.resolve("sensitive-host.example", Duration.ofMillis(10)));

            assertEquals(OutboundHttpFailure.Kind.DNS_FAILURE, failure.kind());
            assertFalse(failure.getMessage().contains("sensitive-host.example"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void mapsResolverFailuresToTheBoundedDnsFailureTaxonomy() {
        assertResolverFailure(OutboundHttpFailure.Kind.DNS_FAILURE, new UnknownHostException("raw resolver detail"));
        assertResolverFailure(OutboundHttpFailure.Kind.INTERNAL_FAILURE, new IOException("raw resolver detail"));
    }

    @Test
    void alreadyInterruptedCallerFailsInternallyWithoutCallingTheResolver() {
        AtomicBoolean resolverCalled = new AtomicBoolean();
        try (var lookup = new BoundedDnsLookup(1, 1, hostname -> {
            resolverCalled.set(true);
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        })) {
            try {
                Thread.currentThread().interrupt();
                OutboundHttpFailure failure = assertThrows(OutboundHttpFailure.class,
                        () -> lookup.resolve("cancelled.example", Duration.ofSeconds(1)));
                assertEquals(OutboundHttpFailure.Kind.INTERNAL_FAILURE, failure.kind());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
        assertFalse(resolverCalled.get());
    }

    private static void assertResolverFailure(OutboundHttpFailure.Kind expected, IOException resolverFailure) {
        try (var lookup = new BoundedDnsLookup(1, 1, hostname -> {
            throw resolverFailure;
        })) {
            OutboundHttpFailure failure = assertThrows(
                    OutboundHttpFailure.class,
                    () -> lookup.resolve("missing.example", Duration.ofSeconds(1)));
            assertEquals(expected, failure.kind());
        }
    }
}

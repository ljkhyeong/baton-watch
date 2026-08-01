package com.personal.baton.watch.adapter.out.external.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class BoundedDnsLookupTest {

    @Test
    void boundsAPlatformLookupAndDoesNotExposeTheHostnameInItsFailure() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        BoundedDnsLookup lookup = new BoundedDnsLookup(executor, hostname -> {
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new UnknownHostException();
            }
            return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
        });

        try {
            DnsLookupException failure = assertThrows(
                    DnsLookupException.class,
                    () -> lookup.resolve("sensitive-host.example", Duration.ofMillis(10)));

            assertEquals(DnsLookupException.Reason.TIMED_OUT, failure.reason());
            assertFalse(failure.getMessage().contains("sensitive-host.example"));
        } finally {
            release.countDown();
            lookup.close();
        }
    }

    @Test
    void mapsUnknownHostToTheBoundedDnsFailureTaxonomy() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        BoundedDnsLookup lookup = new BoundedDnsLookup(executor, hostname -> {
            throw new UnknownHostException("raw resolver detail");
        });

        try {
            DnsLookupException failure = assertThrows(
                    DnsLookupException.class,
                    () -> lookup.resolve("missing.example", Duration.ofSeconds(1)));
            assertEquals(DnsLookupException.Reason.NOT_FOUND, failure.reason());
        } finally {
            lookup.close();
        }
    }
}

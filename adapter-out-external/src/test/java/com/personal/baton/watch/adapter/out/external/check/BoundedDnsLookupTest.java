package com.personal.baton.watch.adapter.out.external.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedDnsLookupTest {

    @Test
    void rejectsExecutorBoundsBeforeAllocatingThreadsOrQueues() {
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

            assertEquals(DnsLookupException.Reason.DNS_FAILURE, failure.reason());
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
            assertEquals(DnsLookupException.Reason.DNS_FAILURE, failure.reason());
        } finally {
            lookup.close();
        }
    }

    @Test
    void mapsRejectedExecutorWorkToAnInternalFailure() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();

        try (BoundedDnsLookup lookup = new BoundedDnsLookup(
                executor,
                hostname -> new InetAddress[] {InetAddress.getLoopbackAddress()})) {
            DnsLookupException failure = assertThrows(
                    DnsLookupException.class,
                    () -> lookup.resolve("public.example", Duration.ofSeconds(1)));

            assertEquals(DnsLookupException.Reason.INTERNAL_FAILURE, failure.reason());
        }
    }

    @Test
    void shutdownCancelsQueuedLookupsWithoutWaitingForTheirDeadline() throws Exception {
        ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(1);
        ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicBoolean resolverCalled = new AtomicBoolean();
        BoundedDnsLookup lookup = new BoundedDnsLookup(executor, hostname -> {
            resolverCalled.set(true);
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try {
            executor.execute(() -> {
                try {
                    releaseWorker.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            Future<DnsLookupException> result = caller.submit(() -> assertThrows(
                    DnsLookupException.class,
                    () -> lookup.resolve("queued.example", Duration.ofSeconds(10))));
            Runnable queued = queue.poll(1, TimeUnit.SECONDS);
            assertNotNull(queued);
            queue.add(queued);

            lookup.close();

            assertEquals(
                    DnsLookupException.Reason.INTERNAL_FAILURE,
                    result.get(1, TimeUnit.SECONDS).reason());
            assertFalse(resolverCalled.get());
        } finally {
            releaseWorker.countDown();
            lookup.close();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void callerInterruptionCancelsTheResolverAndRestoresTheInterruptStatus() throws Exception {
        CountDownLatch resolverStarted = new CountDownLatch(1);
        CountDownLatch resolverInterrupted = new CountDownLatch(1);
        CountDownLatch blockResolver = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<DnsLookupException> failure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        BoundedDnsLookup lookup = new BoundedDnsLookup(executor, hostname -> {
            resolverStarted.countDown();
            try {
                blockResolver.await();
            } catch (InterruptedException exception) {
                resolverInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new UnknownHostException();
            }
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        });
        Thread caller = Thread.ofPlatform().unstarted(() -> {
            try {
                lookup.resolve("public.example", Duration.ofSeconds(5));
            } catch (DnsLookupException exception) {
                failure.set(exception);
            } finally {
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        });

        try {
            caller.start();
            assertTrue(resolverStarted.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(1_000);

            assertFalse(caller.isAlive());
            assertEquals(DnsLookupException.Reason.INTERNAL_FAILURE, failure.get().reason());
            assertTrue(interruptRestored.get());
            assertTrue(resolverInterrupted.await(1, TimeUnit.SECONDS));
        } finally {
            blockResolver.countDown();
            caller.interrupt();
            caller.join(1_000);
            lookup.close();
        }
    }

    @Test
    void createsNamedDaemonThreads() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        try (BoundedDnsLookup lookup = new BoundedDnsLookup(1, 1, hostname -> {
            worker.set(Thread.currentThread());
            return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
        })) {
            lookup.resolve("public.example", Duration.ofSeconds(1));
        }

        assertTrue(worker.get().getName().startsWith("watch-dns-"));
        assertTrue(worker.get().isDaemon());
    }
}

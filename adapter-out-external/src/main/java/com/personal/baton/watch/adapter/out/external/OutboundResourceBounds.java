package com.personal.baton.watch.adapter.out.external;

import org.apache.hc.core5.util.Args;

/** 운영자가 구성할 수 있는 아웃바운드 HTTP 리소스의 강제 상한. */
public final class OutboundResourceBounds {

    public static final long MAX_CHECK_RESPONSE_BYTES = 1024L * 1024L;
    public static final long MAX_EVENT_DELIVERY_RESPONSE_BYTES = 64L * 1024L;
    public static final int MAX_HEADER_COUNT = 200;
    public static final int MAX_HEADER_LINE_LENGTH = 16 * 1024;
    public static final int MAX_DNS_THREADS = 8;
    public static final int MAX_DNS_QUEUE_CAPACITY = 64;
    public static final int MAX_REQUEST_THREADS = 4;
    public static final int MAX_REQUEST_QUEUE_CAPACITY = 16;

    private OutboundResourceBounds() {}

    public static void requireResponseBytes(long value, long maximum) {
        Args.checkRange(value, 1, maximum, "maxResponseBytes");
    }

    public static void requireHeaderBounds(int count, int lineLength) {
        Args.checkRange(count, 1, MAX_HEADER_COUNT, "maxHeaderCount");
        Args.checkRange(lineLength, 1, MAX_HEADER_LINE_LENGTH, "maxHeaderLineLength");
    }

    public static void requireDnsExecutorBounds(int threadCount, int queueCapacity) {
        Args.checkRange(threadCount, 1, MAX_DNS_THREADS, "DNS thread count");
        Args.checkRange(queueCapacity, 1, MAX_DNS_QUEUE_CAPACITY, "DNS queue capacity");
    }

    public static void requireRequestExecutorBounds(int threadCount, int queueCapacity) {
        Args.checkRange(threadCount, 1, MAX_REQUEST_THREADS, "HTTP thread count");
        Args.checkRange(queueCapacity, 1, MAX_REQUEST_QUEUE_CAPACITY, "HTTP queue capacity");
    }
}

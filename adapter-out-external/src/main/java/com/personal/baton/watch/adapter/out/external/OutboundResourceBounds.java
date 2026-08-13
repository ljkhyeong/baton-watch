package com.personal.baton.watch.adapter.out.external;

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
        if (value <= 0 || value > maximum) {
            throw new IllegalArgumentException(
                    "maxResponseBytes must be between 1 and " + maximum);
        }
    }

    public static void requireHeaderBounds(int count, int lineLength) {
        if (count <= 0 || count > MAX_HEADER_COUNT) {
            throw new IllegalArgumentException(
                    "maxHeaderCount must be between 1 and " + MAX_HEADER_COUNT);
        }
        if (lineLength <= 0 || lineLength > MAX_HEADER_LINE_LENGTH) {
            throw new IllegalArgumentException(
                    "maxHeaderLineLength must be between 1 and " + MAX_HEADER_LINE_LENGTH);
        }
    }

    public static void requireDnsExecutorBounds(int threadCount, int queueCapacity) {
        if (threadCount <= 0 || threadCount > MAX_DNS_THREADS) {
            throw new IllegalArgumentException(
                    "DNS thread count must be between 1 and " + MAX_DNS_THREADS);
        }
        if (queueCapacity <= 0 || queueCapacity > MAX_DNS_QUEUE_CAPACITY) {
            throw new IllegalArgumentException(
                    "DNS queue capacity must be between 1 and " + MAX_DNS_QUEUE_CAPACITY);
        }
    }

    public static void requireRequestExecutorBounds(int threadCount, int queueCapacity) {
        if (threadCount <= 0 || threadCount > MAX_REQUEST_THREADS) {
            throw new IllegalArgumentException(
                    "HTTP thread count must be between 1 and " + MAX_REQUEST_THREADS);
        }
        if (queueCapacity <= 0 || queueCapacity > MAX_REQUEST_QUEUE_CAPACITY) {
            throw new IllegalArgumentException(
                    "HTTP queue capacity must be between 1 and " + MAX_REQUEST_QUEUE_CAPACITY);
        }
    }
}

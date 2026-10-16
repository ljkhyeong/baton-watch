package com.personal.baton.watch.adapter.out.external;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OutboundResourceBoundsTest {

    @Test
    void acceptsHeaderAndExecutorHardCeilings() {
        OutboundResourceBounds.requireHeaderBounds(
                OutboundResourceBounds.MAX_HEADER_COUNT,
                OutboundResourceBounds.MAX_HEADER_LINE_LENGTH);
        OutboundResourceBounds.requireDnsExecutorBounds(
                OutboundResourceBounds.MAX_DNS_THREADS,
                OutboundResourceBounds.MAX_DNS_QUEUE_CAPACITY);
        OutboundResourceBounds.requireRequestExecutorBounds(
                OutboundResourceBounds.MAX_REQUEST_THREADS,
                OutboundResourceBounds.MAX_REQUEST_QUEUE_CAPACITY);
    }

    @Test
    void rejectsHeaderBoundsAboveTheHardCeiling() {
        // 실행기 상한 초과는 BoundedDnsLookupTest·ApacheHttpRequestExecutorTest의 생성자 검증에서 확인한다.
        assertThrows(IllegalArgumentException.class, () ->
                OutboundResourceBounds.requireHeaderBounds(
                        OutboundResourceBounds.MAX_HEADER_COUNT + 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
                OutboundResourceBounds.requireHeaderBounds(
                        1, OutboundResourceBounds.MAX_HEADER_LINE_LENGTH + 1));
    }
}

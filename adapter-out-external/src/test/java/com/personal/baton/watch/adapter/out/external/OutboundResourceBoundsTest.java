package com.personal.baton.watch.adapter.out.external;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OutboundResourceBoundsTest {

    @Test
    void acceptsEveryHardCeiling() {
        assertDoesNotThrow(() -> OutboundResourceBounds.requireResponseBytes(
                OutboundResourceBounds.MAX_CHECK_RESPONSE_BYTES,
                OutboundResourceBounds.MAX_CHECK_RESPONSE_BYTES));
        assertDoesNotThrow(() -> OutboundResourceBounds.requireResponseBytes(
                OutboundResourceBounds.MAX_EVENT_DELIVERY_RESPONSE_BYTES,
                OutboundResourceBounds.MAX_EVENT_DELIVERY_RESPONSE_BYTES));
        assertDoesNotThrow(() -> OutboundResourceBounds.requireHeaderBounds(
                OutboundResourceBounds.MAX_HEADER_COUNT,
                OutboundResourceBounds.MAX_HEADER_LINE_LENGTH));
        assertDoesNotThrow(() -> OutboundResourceBounds.requireDnsExecutorBounds(
                OutboundResourceBounds.MAX_DNS_THREADS,
                OutboundResourceBounds.MAX_DNS_QUEUE_CAPACITY));
        assertDoesNotThrow(() -> OutboundResourceBounds.requireRequestExecutorBounds(
                OutboundResourceBounds.MAX_REQUEST_THREADS,
                OutboundResourceBounds.MAX_REQUEST_QUEUE_CAPACITY));
    }

    @Test
    void rejectsValuesAboveEveryHardCeiling() {
        assertThrows(IllegalArgumentException.class, () ->
                OutboundResourceBounds.requireResponseBytes(
                        OutboundResourceBounds.MAX_EVENT_DELIVERY_RESPONSE_BYTES + 1,
                        OutboundResourceBounds.MAX_EVENT_DELIVERY_RESPONSE_BYTES));
        assertThrows(IllegalArgumentException.class, () ->
                OutboundResourceBounds.requireHeaderBounds(
                        OutboundResourceBounds.MAX_HEADER_COUNT + 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
                OutboundResourceBounds.requireHeaderBounds(
                        1, OutboundResourceBounds.MAX_HEADER_LINE_LENGTH + 1));
        assertThrows(IllegalArgumentException.class, () ->
                OutboundResourceBounds.requireDnsExecutorBounds(
                        OutboundResourceBounds.MAX_DNS_THREADS + 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
                OutboundResourceBounds.requireDnsExecutorBounds(
                        1, OutboundResourceBounds.MAX_DNS_QUEUE_CAPACITY + 1));
        assertThrows(IllegalArgumentException.class, () ->
                OutboundResourceBounds.requireRequestExecutorBounds(
                        OutboundResourceBounds.MAX_REQUEST_THREADS + 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
                OutboundResourceBounds.requireRequestExecutorBounds(
                        1, OutboundResourceBounds.MAX_REQUEST_QUEUE_CAPACITY + 1));
    }
}

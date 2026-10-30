package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

/** 생성 시 고정한 콜백 주소로 승인 주소에 고정한 POST 하나를 보낸다. */
@FunctionalInterface
interface DeliveryTransport extends AutoCloseable {

    DeliveryResponse execute(
            List<InetAddress> addresses, byte[] payload, String idempotencyKey, Duration remainingTime)
            throws OutboundHttpFailure;

    /** 실행기를 소유한 구현만 닫는다. */
    @Override
    default void close() {}
}

package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import java.time.Duration;

interface HttpHopTransport extends AutoCloseable {

    HttpHopResponse execute(ApprovedTarget target, Duration remainingTime)
            throws OutboundHttpFailure;

    /** 실행기를 소유한 구현만 닫는다. */
    @Override
    default void close() {}
}

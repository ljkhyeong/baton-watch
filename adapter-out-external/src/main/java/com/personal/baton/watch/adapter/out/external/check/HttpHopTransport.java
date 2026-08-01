package com.personal.baton.watch.adapter.out.external.check;

import java.time.Duration;

interface HttpHopTransport {

    HttpHopResponse execute(ApprovedTarget target, Duration remainingTime, long remainingBytes)
            throws TransportFailure;
}

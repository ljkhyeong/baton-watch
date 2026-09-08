package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import java.time.Duration;

interface HttpHopTransport {

    HttpHopResponse execute(ApprovedTarget target, Duration remainingTime)
            throws OutboundHttpFailure;
}

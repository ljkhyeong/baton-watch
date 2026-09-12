package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import java.time.Duration;

@FunctionalInterface
interface DeliveryTransport {

    DeliveryResponse execute(ApprovedDeliveryRequest request, Duration remainingTime) throws OutboundHttpFailure;
}

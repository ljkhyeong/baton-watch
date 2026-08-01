package com.personal.baton.watch.adapter.out.external.delivery;

import java.time.Duration;

@FunctionalInterface
interface DeliveryTransport {

    DeliveryHttpResponse execute(ApprovedDeliveryRequest request, Duration remainingTime)
            throws DeliveryTransportFailure;
}

package com.personal.baton.watch.adapter.out.external.delivery;

import java.time.Duration;

@FunctionalInterface
interface DeliveryTransport {

    int execute(ApprovedDeliveryRequest request, Duration remainingTime) throws DeliveryTransportFailure;
}

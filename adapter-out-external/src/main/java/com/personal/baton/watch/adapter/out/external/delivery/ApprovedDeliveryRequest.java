package com.personal.baton.watch.adapter.out.external.delivery;

import java.net.InetAddress;
import java.util.List;

record ApprovedDeliveryRequest(
        ValidatedDeliveryEndpoint endpoint,
        List<InetAddress> addresses,
        byte[] payload,
        String bearerToken,
        String idempotencyKey) {

    @Override
    public String toString() {
        return "[approved-delivery-request]";
    }
}

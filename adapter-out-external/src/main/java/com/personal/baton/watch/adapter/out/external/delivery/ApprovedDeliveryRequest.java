package com.personal.baton.watch.adapter.out.external.delivery;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import org.apache.hc.core5.util.Args;

record ApprovedDeliveryRequest(
        ValidatedDeliveryEndpoint endpoint,
        List<InetAddress> addresses,
        byte[] payload,
        String bearerToken,
        String idempotencyKey) {

    ApprovedDeliveryRequest {
        Objects.requireNonNull(endpoint, "endpoint");
        addresses = List.copyOf(Args.notEmpty(addresses, "approvedAddresses"));
        payload = Objects.requireNonNull(payload, "payload").clone();
        Objects.requireNonNull(bearerToken, "bearerToken");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public String toString() {
        return "[approved-delivery-request]";
    }
}

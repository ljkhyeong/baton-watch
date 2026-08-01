package com.personal.baton.watch.adapter.out.external.delivery;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

final class ApprovedDeliveryRequest {

    private final ValidatedDeliveryEndpoint endpoint;
    private final List<InetAddress> addresses;
    private final byte[] payload;
    private final String bearerToken;
    private final String idempotencyKey;

    ApprovedDeliveryRequest(
            ValidatedDeliveryEndpoint endpoint,
            List<InetAddress> addresses,
            byte[] payload,
            String bearerToken,
            String idempotencyKey) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.addresses = List.copyOf(Objects.requireNonNull(addresses, "addresses"));
        this.payload = Objects.requireNonNull(payload, "payload").clone();
        this.bearerToken = Objects.requireNonNull(bearerToken, "bearerToken");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (this.addresses.isEmpty()) {
            throw new IllegalArgumentException("approved address set must not be empty");
        }
    }

    ValidatedDeliveryEndpoint endpoint() {
        return endpoint;
    }

    List<InetAddress> addresses() {
        return addresses;
    }

    byte[] payload() {
        return payload.clone();
    }

    String bearerToken() {
        return bearerToken;
    }

    String idempotencyKey() {
        return idempotencyKey;
    }
}

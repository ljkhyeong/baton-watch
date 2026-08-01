package com.personal.baton.watch.adapter.out.external.delivery;

final class DeliveryTransportFailure extends Exception {

    enum Kind {
        CONNECT_TIMEOUT,
        READ_TIMEOUT,
        TLS_FAILURE,
        RESPONSE_TOO_LARGE,
        NETWORK_FAILURE,
        INTERNAL_FAILURE
    }

    private final Kind kind;

    DeliveryTransportFailure(Kind kind) {
        super("event delivery transport failed");
        this.kind = kind;
    }

    Kind kind() {
        return kind;
    }
}

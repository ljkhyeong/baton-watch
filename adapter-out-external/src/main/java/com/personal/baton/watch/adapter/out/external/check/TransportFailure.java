package com.personal.baton.watch.adapter.out.external.check;

final class TransportFailure extends Exception {

    enum Kind {
        CONNECT_TIMEOUT,
        READ_TIMEOUT,
        TLS_FAILURE,
        RESPONSE_TOO_LARGE,
        NETWORK_FAILURE,
        INTERNAL_FAILURE
    }

    private final Kind kind;
    private final long responseBytes;

    TransportFailure(Kind kind, long responseBytes) {
        super("HTTP transport failed");
        if (responseBytes < 0) {
            throw new IllegalArgumentException("responseBytes must be non-negative");
        }
        this.kind = kind;
        this.responseBytes = responseBytes;
    }

    Kind kind() {
        return kind;
    }

    long responseBytes() {
        return responseBytes;
    }
}

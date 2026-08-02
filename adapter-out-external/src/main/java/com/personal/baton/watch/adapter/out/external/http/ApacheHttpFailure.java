package com.personal.baton.watch.adapter.out.external.http;

/** Bounded failure taxonomy shared by the Apache HTTP infrastructure. */
public final class ApacheHttpFailure extends Exception {

    public enum Kind {
        CONNECT_TIMEOUT,
        READ_TIMEOUT,
        TLS_FAILURE,
        RESPONSE_TOO_LARGE,
        NETWORK_FAILURE,
        INTERNAL_FAILURE
    }

    private final Kind kind;
    private final long responseBytes;

    ApacheHttpFailure(Kind kind, long responseBytes) {
        super("Apache HTTP request failed");
        if (responseBytes < 0) {
            throw new IllegalArgumentException("responseBytes must be non-negative");
        }
        this.kind = kind;
        this.responseBytes = responseBytes;
    }

    public Kind kind() {
        return kind;
    }

    public long responseBytes() {
        return responseBytes;
    }
}

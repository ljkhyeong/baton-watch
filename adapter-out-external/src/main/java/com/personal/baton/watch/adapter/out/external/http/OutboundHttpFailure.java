package com.personal.baton.watch.adapter.out.external.http;

import org.apache.hc.core5.util.Args;

/** 아웃바운드 HTTP 계층이 공유하는 제한된 실패 분류 체계. */
public final class OutboundHttpFailure extends Exception {

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

    public OutboundHttpFailure(Kind kind, long responseBytes) {
        super("outbound HTTP request failed");
        Args.notNegative(responseBytes, "responseBytes");
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

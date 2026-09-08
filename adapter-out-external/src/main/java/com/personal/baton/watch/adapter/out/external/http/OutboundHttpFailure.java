package com.personal.baton.watch.adapter.out.external.http;

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

    public OutboundHttpFailure(Kind kind) {
        super("outbound HTTP request failed");
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}

package com.personal.baton.watch.adapter.out.external.http;

/**
 * DNS 조회·주소 승인·HTTP 실행이 공유하는 제한된 실패 분류 체계.
 * HTTP 실행기는 DNS 관련 분류를 만들지 않으며 고정 리졸버 불일치는 {@code INTERNAL_FAILURE}다.
 */
public final class OutboundHttpFailure extends Exception {

    public enum Kind {
        DESTINATION_REJECTED,
        DNS_FAILURE,
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

package com.personal.baton.watch.domain.monitoring;

public enum CheckOutcome {
    SUCCESS,
    HTTP_CLIENT_ERROR,
    HTTP_SERVER_ERROR,
    DESTINATION_REJECTED,
    DNS_FAILURE,
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    TLS_FAILURE,
    REDIRECT_REJECTED,
    TOO_MANY_REDIRECTS,
    RESPONSE_TOO_LARGE,
    NETWORK_FAILURE,
    INTERNAL_FAILURE;

    public boolean isConclusive() {
        return this != INTERNAL_FAILURE;
    }

    /** 이 결과 자체가 대상의 HTTP 응답을 받았다는 뜻인지 여부다. 리다이렉트 응답 뒤의 실패는 따로 판단한다. */
    public boolean receivedResponse() {
        return switch (this) {
            case SUCCESS, HTTP_CLIENT_ERROR, HTTP_SERVER_ERROR,
                    REDIRECT_REJECTED, TOO_MANY_REDIRECTS, RESPONSE_TOO_LARGE -> true;
            case DESTINATION_REJECTED, DNS_FAILURE, CONNECT_TIMEOUT, READ_TIMEOUT,
                    TLS_FAILURE, NETWORK_FAILURE, INTERNAL_FAILURE -> false;
        };
    }
}

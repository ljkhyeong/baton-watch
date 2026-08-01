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

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isConclusive() {
        return this != INTERNAL_FAILURE;
    }

    public boolean isTargetFailure() {
        return isConclusive() && !isSuccess();
    }
}

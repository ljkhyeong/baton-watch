package com.personal.baton.watch.application.monitoring.model;

public enum EventDeliveryOutcome {
    DELIVERED,
    HTTP_CLIENT_ERROR,
    HTTP_SERVER_ERROR,
    DESTINATION_REJECTED,
    DNS_FAILURE,
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    TLS_FAILURE,
    RESPONSE_TOO_LARGE,
    NETWORK_FAILURE,
    INTERNAL_FAILURE;

    public boolean isDelivered() {
        return this == DELIVERED;
    }
}

package com.personal.baton.watch.adapter.out.external.delivery;

record DeliveryHttpResponse(int statusCode, long responseBytes) {

    DeliveryHttpResponse {
        if (responseBytes < 0) {
            throw new IllegalArgumentException("responseBytes must be non-negative");
        }
    }
}

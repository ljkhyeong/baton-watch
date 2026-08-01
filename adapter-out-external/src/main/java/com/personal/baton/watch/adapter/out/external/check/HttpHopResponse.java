package com.personal.baton.watch.adapter.out.external.check;

import java.util.List;

record HttpHopResponse(int statusCode, List<String> locations, long responseBytes) {

    HttpHopResponse {
        locations = List.copyOf(locations);
        if (responseBytes < 0) {
            throw new IllegalArgumentException("responseBytes must be non-negative");
        }
    }

    static HttpHopResponse finalStatus(int statusCode, long responseBytes) {
        return new HttpHopResponse(statusCode, List.of(), responseBytes);
    }

    static HttpHopResponse redirect(int statusCode, String location, long responseBytes) {
        return new HttpHopResponse(statusCode, List.of(location), responseBytes);
    }
}

package com.personal.baton.watch.adapter.out.external.check;

import java.util.List;

record HttpHopResponse(int statusCode, List<String> locations, long responseBytes) {

    HttpHopResponse {
        locations = List.copyOf(locations);
        if (responseBytes < 0) {
            throw new IllegalArgumentException("responseBytes must be non-negative");
        }
    }
}

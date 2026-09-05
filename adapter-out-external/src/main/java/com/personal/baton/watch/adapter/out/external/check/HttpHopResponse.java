package com.personal.baton.watch.adapter.out.external.check;

import java.util.List;

record HttpHopResponse(int statusCode, List<String> locations) {

    HttpHopResponse {
        locations = List.copyOf(locations);
    }
}

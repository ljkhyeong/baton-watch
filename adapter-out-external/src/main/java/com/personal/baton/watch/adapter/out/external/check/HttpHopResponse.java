package com.personal.baton.watch.adapter.out.external.check;

import java.util.List;
import org.apache.hc.core5.util.Args;

record HttpHopResponse(int statusCode, List<String> locations, long responseBytes) {

    HttpHopResponse {
        locations = List.copyOf(locations);
        Args.notNegative(responseBytes, "responseBytes");
    }
}

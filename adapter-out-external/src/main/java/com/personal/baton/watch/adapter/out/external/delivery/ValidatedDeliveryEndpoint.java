package com.personal.baton.watch.adapter.out.external.delivery;

import java.net.URI;
import java.util.Objects;

record ValidatedDeliveryEndpoint(URI uri, String hostname) {

    ValidatedDeliveryEndpoint {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(hostname, "hostname");
    }
}

package com.personal.baton.watch.adapter.out.external.check;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

record ApprovedTarget(ValidatedUri target, List<InetAddress> addresses) {

    ApprovedTarget {
        Objects.requireNonNull(target, "target");
        addresses = List.copyOf(Objects.requireNonNull(addresses, "addresses"));
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("approved address set must not be empty");
        }
    }
}

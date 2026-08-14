package com.personal.baton.watch.adapter.out.external.check;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import org.apache.hc.core5.util.Args;

record ApprovedTarget(ValidatedUri target, List<InetAddress> addresses) {

    ApprovedTarget {
        Objects.requireNonNull(target, "target");
        addresses = List.copyOf(Args.notEmpty(addresses, "approvedAddresses"));
    }
}

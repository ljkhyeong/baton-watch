package com.personal.baton.watch.adapter.out.external.check;

final class DnsLookupException extends Exception {

    enum Reason {
        NOT_FOUND,
        TIMED_OUT,
        CAPACITY_EXHAUSTED,
        INTERRUPTED,
        FAILED
    }

    private final Reason reason;

    DnsLookupException(Reason reason) {
        super("DNS lookup failed");
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}

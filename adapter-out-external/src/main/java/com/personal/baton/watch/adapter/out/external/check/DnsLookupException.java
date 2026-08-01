package com.personal.baton.watch.adapter.out.external.check;

public final class DnsLookupException extends Exception {

    public enum Reason {
        NOT_FOUND,
        TIMED_OUT,
        CAPACITY_EXHAUSTED,
        INTERRUPTED,
        FAILED
    }

    private final Reason reason;

    public DnsLookupException(Reason reason) {
        super("DNS lookup failed");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

package com.personal.baton.watch.adapter.out.external.check;

public final class DnsLookupException extends Exception {

    public enum Reason {
        DNS_FAILURE,
        INTERNAL_FAILURE
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

package com.personal.baton.watch.adapter.out.external.check;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.hc.client5.http.DnsResolver;

/** A resolver scoped to one approved request target; it never performs DNS. */
final class PinnedDnsResolver implements DnsResolver {

    private final String expectedHostname;
    private final String comparisonHostname;
    private final InetAddress[] approvedAddresses;

    PinnedDnsResolver(String expectedHostname, List<InetAddress> approvedAddresses) {
        this.expectedHostname = Objects.requireNonNull(expectedHostname, "expectedHostname");
        this.comparisonHostname = comparisonKey(expectedHostname);
        if (approvedAddresses == null || approvedAddresses.isEmpty()) {
            throw new IllegalArgumentException("approved address set must not be empty");
        }
        this.approvedAddresses = approvedAddresses.toArray(InetAddress[]::new);
    }

    @Override
    public InetAddress[] resolve(String hostname) throws UnknownHostException {
        requireExpectedHost(hostname);
        return approvedAddresses.clone();
    }

    @Override
    public String resolveCanonicalHostname(String hostname) throws UnknownHostException {
        requireExpectedHost(hostname);
        return expectedHostname;
    }

    private void requireExpectedHost(String hostname) throws UnknownHostException {
        if (hostname == null || !comparisonHostname.equals(comparisonKey(hostname))) {
            throw new UnknownHostException("request host is outside the approved DNS scope");
        }
    }

    private static String comparisonKey(String hostname) {
        return hostname.toLowerCase(Locale.ROOT);
    }
}

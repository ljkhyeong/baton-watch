package com.personal.baton.watch.adapter.out.external.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.core5.util.Args;

/** 승인된 요청 대상 하나로 범위가 제한된 리졸버이며 DNS 조회를 절대 수행하지 않는다. */
final class PinnedDnsResolver implements DnsResolver {

    private final String expectedHostname;
    private final InetAddress[] approvedAddresses;

    PinnedDnsResolver(String expectedHostname, List<InetAddress> approvedAddresses) {
        this.expectedHostname = Objects.requireNonNull(expectedHostname, "expectedHostname");
        this.approvedAddresses = Args.notEmpty(approvedAddresses, "approvedAddresses")
                .toArray(InetAddress[]::new);
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
        if (!expectedHostname.equalsIgnoreCase(hostname)) {
            throw new UnknownHostException("request host is outside the approved DNS scope");
        }
    }
}

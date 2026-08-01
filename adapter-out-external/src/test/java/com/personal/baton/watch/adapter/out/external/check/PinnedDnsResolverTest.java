package com.personal.baton.watch.adapter.out.external.check;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PinnedDnsResolverTest {

    @Test
    void returnsOnlyTheApprovedAddressesForTheOriginalHostname() throws Exception {
        InetAddress first = InetAddress.getByName("8.8.8.8");
        InetAddress second = InetAddress.getByName("1.1.1.1");
        PinnedDnsResolver resolver = new PinnedDnsResolver("Example.COM", List.of(first, second));

        InetAddress[] resolved = resolver.resolve("example.com");
        resolved[0] = InetAddress.getByName("9.9.9.9");

        assertArrayEquals(new InetAddress[] {first, second}, resolver.resolve("EXAMPLE.COM"));
        assertEquals("Example.COM", resolver.resolveCanonicalHostname("example.com"));
    }

    @Test
    void refusesAnyHostnameOutsideTheRequestScope() throws Exception {
        PinnedDnsResolver resolver = new PinnedDnsResolver(
                "example.com", List.of(InetAddress.getByName("8.8.8.8")));

        assertThrows(UnknownHostException.class, () -> resolver.resolve("redirect.example"));
        assertThrows(UnknownHostException.class, () -> resolver.resolveCanonicalHostname("evil.example"));
    }
}

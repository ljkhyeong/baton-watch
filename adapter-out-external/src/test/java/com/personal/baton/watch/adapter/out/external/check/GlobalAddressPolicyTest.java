package com.personal.baton.watch.adapter.out.external.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GlobalAddressPolicyTest {

    private final GlobalAddressPolicy policy = new GlobalAddressPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
        "8.8.8.8",
        "1.1.1.1",
        "2001:db7:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:db9::1",
        "2001:4860:4860::8888",
        "2606:4700:4700::1111",
        "2620:4f:7fff:ffff:ffff:ffff:ffff:ffff",
        "2620:4f:8001::1"
    })
    void acceptsPublicGlobalUnicast(String literal) throws Exception {
        assertTrue(policy.isGlobal(InetAddress.getByName(literal)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2001:200::",
        "2001:3ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:400::",
        "2001:7ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:800::",
        "2001:fff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:1200::",
        "2001:13ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:1400::",
        "2001:17ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:1800::",
        "2001:1fff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:2000::",
        "2001:3fff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:4000::",
        "2001:47ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:4800::",
        "2001:4bff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:4c00::",
        "2001:4dff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:5000::",
        "2001:5fff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:8000::",
        "2001:bfff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2003::",
        "2003:3fff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2400::",
        "241f:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2600::",
        "260f:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2610::",
        "2610:1ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2620::",
        "2620:1ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2630::",
        "263f:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2800::",
        "280f:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2a00::",
        "2a1f:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2c00::",
        "2c0f:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
    })
    void acceptsAllocatedIpv6RangeBoundaries(String literal) throws Exception {
        assertTrue(policy.isGlobal(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0.0.0.0",
        "10.1.2.3",
        "100.64.0.1",
        "127.0.0.1",
        "169.254.169.254",
        "172.16.0.1",
        "192.0.0.9",
        "192.0.2.1",
        "192.31.196.1",
        "192.52.193.1",
        "192.88.99.1",
        "192.168.1.1",
        "192.175.48.1",
        "198.18.0.1",
        "198.51.100.1",
        "203.0.113.1",
        "224.0.0.1",
        "239.255.255.250",
        "240.0.0.1",
        "255.255.255.255",
        "::",
        "::1",
        "::ffff:192.0.2.1",
        "64:ff9b::808:808",
        "64:ff9b:1::1",
        "100::1",
        "2001::1",
        "2001:2::1",
        "2001:10::1",
        "2001:20::1",
        "2001:db8::",
        "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff",
        "2002::1",
        "2620:4f:8000::",
        "2620:4f:8000:ffff:ffff:ffff:ffff:ffff",
        "3fff::1",
        "5f00::1",
        "fc00::1",
        "fd00::1",
        "fe80::1",
        "fec0::1",
        "ff02::1"
    })
    void rejectsSpecialUseAndNonGlobalRanges(String literal) throws Exception {
        assertFalse(policy.isGlobal(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2000::1",
        "2001:1000::1",
        "2001:4e00::1",
        "2001:6000::1",
        "2001:c000::1",
        "2003:4000::1",
        "2004::1",
        "2420::1",
        "2610:200::1",
        "2620:200::1",
        "2640::1",
        "2810::1",
        "2a20::1",
        "2c10::1",
        "2d00::1",
        "3000::1",
        "3ffe::1"
    })
    void rejectsUnallocatedOrReservedGlobalUnicastSpace(String literal) throws Exception {
        assertFalse(policy.isGlobal(InetAddress.getByName(literal)), literal);
    }

    @Test
    void rejectsTheCompleteAnswerWhenOneAddressIsPrivate() throws Exception {
        List<InetAddress> mixed = List.of(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("10.0.0.1"));

        assertThrows(AddressPolicyException.class, () -> policy.approve(mixed));
    }

    @Test
    void rejectsAzureWireServerAsAPlatformServiceDestination() throws Exception {
        InetAddress wireServer = InetAddress.getByName("168.63.129.16");

        assertFalse(policy.isGlobal(wireServer));
        assertThrows(AddressPolicyException.class, () -> policy.approve(List.of(wireServer)));
    }

    @Test
    void deduplicatesAnApprovedAnswerWithoutChangingOrder() throws Exception {
        InetAddress first = InetAddress.getByName("8.8.8.8");
        InetAddress second = InetAddress.getByName("1.1.1.1");

        assertEquals(List.of(first, second), policy.approve(List.of(first, first, second)));
    }

    @Test
    void rejectsEmptyAndNullContainingAnswers() throws Exception {
        assertThrows(AddressPolicyException.class, () -> policy.approve(List.of()));
        assertThrows(AddressPolicyException.class, () -> policy.approve(java.util.Arrays.asList(
                InetAddress.getByName("8.8.8.8"), null)));
    }
}

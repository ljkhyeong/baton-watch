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
        "2001:4860:4860::8888",
        "2606:4700:4700::1111"
    })
    void acceptsPublicGlobalUnicast(String literal) throws Exception {
        assertTrue(policy.isGlobal(InetAddress.getByName(literal)));
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
        "2001:db8::1",
        "2002::1",
        "2620:4f:8000::1",
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

    @Test
    void rejectsTheCompleteAnswerWhenOneAddressIsPrivate() throws Exception {
        List<InetAddress> mixed = List.of(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("10.0.0.1"));

        assertThrows(AddressPolicyException.class, () -> policy.approve(mixed));
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

package com.personal.baton.watch.adapter.out.external.check;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rejects an entire DNS answer when any address is not public global unicast. */
final class GlobalAddressPolicy {

    private static final List<Cidr> REJECTED_IPV4 = List.of(
            cidr("0.0.0.0", 8),
            cidr("10.0.0.0", 8),
            cidr("100.64.0.0", 10),
            cidr("127.0.0.0", 8),
            cidr("169.254.0.0", 16),
            cidr("172.16.0.0", 12),
            cidr("192.0.0.0", 24),
            cidr("192.0.2.0", 24),
            cidr("192.31.196.0", 24),
            cidr("192.52.193.0", 24),
            cidr("192.88.99.0", 24),
            cidr("192.168.0.0", 16),
            cidr("192.175.48.0", 24),
            cidr("198.18.0.0", 15),
            cidr("198.51.100.0", 24),
            cidr("203.0.113.0", 24),
            cidr("224.0.0.0", 4),
            cidr("240.0.0.0", 4));

    private static final List<Cidr> REJECTED_IPV6 = List.of(
            cidr("2001:0000::", 23),
            cidr("2001:db8::", 32),
            cidr("2002::", 16),
            cidr("2620:4f:8000::", 48),
            cidr("3fff::", 20));

    List<InetAddress> approve(List<InetAddress> answer) throws AddressPolicyException {
        if (answer == null || answer.isEmpty()) {
            throw new AddressPolicyException();
        }

        Map<AddressBytes, InetAddress> unique = new LinkedHashMap<>();
        for (InetAddress address : answer) {
            if (!isGlobal(address)) {
                throw new AddressPolicyException();
            }
            unique.putIfAbsent(new AddressBytes(address.getAddress()), address);
        }
        return List.copyOf(new ArrayList<>(unique.values()));
    }

    boolean isGlobal(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address) {
            return REJECTED_IPV4.stream().noneMatch(cidr -> cidr.contains(address));
        }
        if (!(address instanceof Inet6Address inet6Address) || inet6Address.getScopeId() != 0) {
            return false;
        }

        byte[] bytes = address.getAddress();
        // Currently allocated public IPv6 global unicast space is 2000::/3.
        if ((bytes[0] & 0xe0) != 0x20) {
            return false;
        }
        return REJECTED_IPV6.stream().noneMatch(cidr -> cidr.contains(address));
    }

    private static Cidr cidr(String network, int prefixLength) {
        try {
            return new Cidr(InetAddress.getByName(network).getAddress(), prefixLength);
        } catch (UnknownHostException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record Cidr(byte[] network, int prefixLength) {

        private Cidr {
            network = network.clone();
            if (prefixLength < 0 || prefixLength > network.length * Byte.SIZE) {
                throw new IllegalArgumentException("invalid CIDR prefix");
            }
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int completeBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            for (int index = 0; index < completeBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (Byte.SIZE - remainingBits);
            return (candidate[completeBytes] & mask) == (network[completeBytes] & mask);
        }

        @Override
        public byte[] network() {
            return network.clone();
        }
    }

    private static final class AddressBytes {

        private final byte[] value;

        private AddressBytes(byte[] value) {
            this.value = value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AddressBytes that && Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }
}

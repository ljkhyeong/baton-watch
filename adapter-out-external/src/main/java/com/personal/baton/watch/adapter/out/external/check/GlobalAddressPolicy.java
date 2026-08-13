package com.personal.baton.watch.adapter.out.external.check;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 주소 중 하나라도 공개 글로벌 유니캐스트가 아니면 DNS 응답 전체를 거부한다. */
public final class GlobalAddressPolicy {

    /**
     * 2025-10-10 일자의 IANA 레지스트리 스냅샷에 등재된, 할당된 공개 IPv6 글로벌 유니캐스트
     * 접두사다. 미등재 범위와 향후 할당은 검토되기 전까지 실패 폐쇄 방식으로 거부한다.
     * IANA 프로토콜 할당과 6to4는 의도적으로 제외했다.
     * 출처: https://www.iana.org/assignments/ipv6-unicast-address-assignments/
     */
    private static final List<Cidr> ALLOCATED_PUBLIC_IPV6 = List.of(
            cidr("2001:200::", 23),
            cidr("2001:400::", 22),
            cidr("2001:800::", 21),
            cidr("2001:1200::", 23),
            cidr("2001:1400::", 22),
            cidr("2001:1800::", 21),
            cidr("2001:2000::", 19),
            cidr("2001:4000::", 21),
            cidr("2001:4800::", 22),
            cidr("2001:4c00::", 23),
            cidr("2001:5000::", 20),
            cidr("2001:8000::", 18),
            cidr("2003::", 18),
            cidr("2400::", 11),
            cidr("2600::", 12),
            cidr("2610::", 23),
            cidr("2620::", 23),
            cidr("2630::", 12),
            cidr("2800::", 12),
            cidr("2a00::", 11),
            cidr("2c00::", 12));

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
            cidr("2001:db8::", 32),
            cidr("2620:4f:8000::", 48));

    public List<InetAddress> approve(List<InetAddress> answer) throws AddressPolicyException {
        if (answer == null || answer.isEmpty()) {
            throw new AddressPolicyException();
        }

        Set<InetAddress> unique = new LinkedHashSet<>();
        for (InetAddress address : answer) {
            if (!isGlobal(address)) {
                throw new AddressPolicyException();
            }
            unique.add(address);
        }
        return List.copyOf(unique);
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

        return REJECTED_IPV6.stream().noneMatch(cidr -> cidr.contains(address))
                && ALLOCATED_PUBLIC_IPV6.stream().anyMatch(cidr -> cidr.contains(address));
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
    }
}

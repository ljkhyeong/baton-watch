package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.http.OutboundHttpFailure;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

public interface DnsLookup extends AutoCloseable {

    List<InetAddress> resolve(String hostname, Duration timeout) throws OutboundHttpFailure;

    /** 실행기를 소유한 구현만 닫는다. */
    @Override
    default void close() {}
}

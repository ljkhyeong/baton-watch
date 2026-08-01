package com.personal.baton.watch.adapter.out.external.check;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

interface DnsLookup {

    List<InetAddress> resolve(String hostname, Duration timeout) throws DnsLookupException;
}

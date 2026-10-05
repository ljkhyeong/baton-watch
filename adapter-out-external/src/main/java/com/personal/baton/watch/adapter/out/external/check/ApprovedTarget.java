package com.personal.baton.watch.adapter.out.external.check;

import java.net.InetAddress;
import java.util.List;

record ApprovedTarget(ValidatedUri target, List<InetAddress> addresses) {}

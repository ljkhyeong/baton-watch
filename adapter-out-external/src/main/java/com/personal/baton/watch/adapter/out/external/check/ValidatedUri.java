package com.personal.baton.watch.adapter.out.external.check;

import java.net.URI;

record ValidatedUri(URI uri, String scheme, String hostname, String loopKey) {
}

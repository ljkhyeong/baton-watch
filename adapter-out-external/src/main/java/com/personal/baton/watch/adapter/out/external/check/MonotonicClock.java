package com.personal.baton.watch.adapter.out.external.check;

@FunctionalInterface
interface MonotonicClock {

    long nanoTime();
}

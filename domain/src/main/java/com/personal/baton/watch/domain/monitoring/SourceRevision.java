package com.personal.baton.watch.domain.monitoring;

public record SourceRevision(long value) implements Comparable<SourceRevision> {

    public SourceRevision {
        if (value < 0) {
            throw new IllegalArgumentException("source revision must be non-negative");
        }
    }

    @Override
    public int compareTo(SourceRevision other) {
        return Long.compare(value, other.value);
    }
}

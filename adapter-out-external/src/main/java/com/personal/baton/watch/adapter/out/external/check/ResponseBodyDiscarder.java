package com.personal.baton.watch.adapter.out.external.check;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.LongConsumer;
import org.apache.hc.core5.http.HttpEntity;

/** Consumes only bounded bytes and never retains response body content. */
final class ResponseBodyDiscarder {

    private static final int BUFFER_SIZE = 8 * 1024;

    long discard(HttpEntity entity, long limit, LongConsumer progress)
            throws IOException, ResponseTooLargeException {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(progress, "progress");
        if (limit <= 0) {
            throw new IllegalArgumentException("response byte limit must be positive");
        }
        long declaredLength = entity.getContentLength();
        if (declaredLength > limit) {
            throw new ResponseTooLargeException(0);
        }

        long consumed = 0;
        byte[] buffer = new byte[(int) Math.min(BUFFER_SIZE, limit)];
        try (InputStream input = entity.getContent()) {
            while (true) {
                int requested = (int) Math.min(buffer.length, limit - consumed);
                if (requested == 0) {
                    // Distinguish an exact-limit body from a longer streaming body.
                    int extra = input.read();
                    if (extra == -1) {
                        return consumed;
                    }
                    throw new ResponseTooLargeException(consumed);
                }
                int read = input.read(buffer, 0, requested);
                if (read == -1) {
                    return consumed;
                }
                if (read == 0) {
                    continue;
                }
                consumed += read;
                progress.accept(consumed);
            }
        }
    }

    static final class ResponseTooLargeException extends IOException {

        private final long consumedWithinLimit;

        ResponseTooLargeException(long consumedWithinLimit) {
            super("response exceeded byte limit");
            this.consumedWithinLimit = consumedWithinLimit;
        }

        long consumedWithinLimit() {
            return consumedWithinLimit;
        }
    }
}

package com.personal.baton.watch.adapter.out.external.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.LongConsumer;
import org.apache.hc.core5.http.HttpEntity;

/**
 * Consumes only bounded bytes and never retains response body content. Failed
 * streams remain open so the owning response can abort instead of draining.
 */
public final class ResponseBodyDiscarder {

    private static final int BUFFER_SIZE = 8 * 1024;

    public long discard(HttpEntity entity, long limit, LongConsumer progress)
            throws IOException, ResponseTooLargeException {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(progress, "progress");
        if (limit < 0) {
            throw new IllegalArgumentException("response byte limit must be non-negative");
        }
        long declaredLength = entity.getContentLength();
        if (declaredLength > limit) {
            throw new ResponseTooLargeException(0);
        }
        if (limit == 0) {
            if (declaredLength == 0) {
                return closeCompleted(entity.getContent(), 0);
            }
            throw new ResponseTooLargeException(0);
        }

        long consumed = 0;
        byte[] buffer = new byte[(int) Math.min(BUFFER_SIZE, limit)];
        InputStream input = entity.getContent();
        while (true) {
            int requested = (int) Math.min(buffer.length, limit - consumed);
            if (requested == 0) {
                if (declaredLength == limit) {
                    return closeCompleted(input, consumed);
                }
                // An unknown-length stream cannot be probed without consuming
                // beyond the cap, so reaching the cap is rejected conservatively.
                throw new ResponseTooLargeException(consumed);
            }
            int read = input.read(buffer, 0, requested);
            if (read == -1) {
                return closeCompleted(input, consumed);
            }
            if (read == 0) {
                continue;
            }
            consumed += read;
            progress.accept(consumed);
        }
    }

    private static long closeCompleted(InputStream input, long consumed) throws IOException {
        input.close();
        return consumed;
    }

    public static final class ResponseTooLargeException extends IOException {

        private final long consumedWithinLimit;

        ResponseTooLargeException(long consumedWithinLimit) {
            super("response exceeded byte limit");
            this.consumedWithinLimit = consumedWithinLimit;
        }

        public long consumedWithinLimit() {
            return consumedWithinLimit;
        }
    }
}

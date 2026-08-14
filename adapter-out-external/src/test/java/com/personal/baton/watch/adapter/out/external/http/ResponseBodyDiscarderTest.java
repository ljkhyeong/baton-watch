package com.personal.baton.watch.adapter.out.external.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ContentTooLongException;
import org.apache.hc.core5.http.io.entity.BasicHttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.junit.jupiter.api.Test;

class ResponseBodyDiscarderTest {

    private final ResponseBodyDiscarder discarder = new ResponseBodyDiscarder();

    @Test
    void discardsAnUnknownLengthBodyBelowTheLimitWithoutRetainingIt() throws Exception {
        AtomicLong progress = new AtomicLong();
        BasicHttpEntity entity = new BasicHttpEntity(
                new ByteArrayInputStream(new byte[63]), -1, ContentType.APPLICATION_OCTET_STREAM);

        long consumed = discarder.discard(entity, 64, progress::set);

        assertEquals(63, consumed);
        assertEquals(63, progress.get());
    }

    @Test
    void acceptsAnExactDeclaredLengthWithoutReadingAProbeByte() throws Exception {
        AtomicLong progress = new AtomicLong();
        CountingInputStream input = new CountingInputStream(
                new ByteArrayInputStream(new byte[65]));
        BasicHttpEntity entity = new BasicHttpEntity(
                input, 64, ContentType.APPLICATION_OCTET_STREAM);

        long consumed = discarder.discard(entity, 64, progress::set);

        assertEquals(64, consumed);
        assertEquals(64, progress.get());
        assertEquals(64, input.bytesRead());
    }

    @Test
    void stopsAnUnknownLengthBodyAtTheCapWithoutReadingAProbeByte() {
        AtomicLong progress = new AtomicLong();
        CountingInputStream input = new CountingInputStream(
                new ByteArrayInputStream(new byte[65]));
        BasicHttpEntity entity = new BasicHttpEntity(
                input, -1, ContentType.APPLICATION_OCTET_STREAM);

        assertThrows(
                ContentTooLongException.class,
                () -> discarder.discard(entity, 64, progress::set));

        assertEquals(64, progress.get());
        assertEquals(64, input.bytesRead());
    }

    @Test
    void rejectsAnUnknownExactLimitConservativelyWithoutReadingPastIt() {
        CountingInputStream input = new CountingInputStream(
                new ByteArrayInputStream(new byte[64]));
        BasicHttpEntity entity = new BasicHttpEntity(
                input, -1, ContentType.APPLICATION_OCTET_STREAM);

        assertThrows(
                ContentTooLongException.class,
                () -> discarder.discard(entity, 64, ignored -> {}));

        assertEquals(64, input.bytesRead());
    }

    @Test
    void rejectsAnOversizedDeclaredLengthBeforeOpeningTheBody() {
        ByteArrayEntity entity = new ByteArrayEntity(new byte[65], ContentType.APPLICATION_OCTET_STREAM);

        assertThrows(
                ContentTooLongException.class,
                () -> discarder.discard(entity, 64, ignored -> {}));
    }

    @Test
    void acceptsADeclaredEmptyBodyWhenNoByteBudgetRemains() throws Exception {
        BasicHttpEntity entity = new BasicHttpEntity(
                new ByteArrayInputStream(new byte[0]), 0, ContentType.APPLICATION_OCTET_STREAM);

        assertEquals(0, discarder.discard(entity, 0, ignored -> {}));
    }

    private static final class CountingInputStream extends FilterInputStream {

        private long bytesRead;

        private CountingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        private long bytesRead() {
            return bytesRead;
        }
    }
}

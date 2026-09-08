package com.personal.baton.watch.adapter.out.external.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ContentTooLongException;
import org.apache.hc.core5.http.io.entity.BasicHttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.junit.jupiter.api.Test;

class ResponseBodyDiscarderTest {

    private final ResponseBodyDiscarder discarder = new ResponseBodyDiscarder();

    @Test
    void discardsAnUnknownLengthBodyBelowTheLimitWithoutRetainingIt() throws Exception {
        CountingInputStream input = new CountingInputStream(
                new ByteArrayInputStream(new byte[63]));
        BasicHttpEntity entity = new BasicHttpEntity(
                input, -1, ContentType.APPLICATION_OCTET_STREAM);

        discarder.discard(entity, 64);

        assertEquals(63, input.bytesRead());
    }

    @Test
    void acceptsAnExactDeclaredLengthWithoutReadingAProbeByte() throws Exception {
        CountingInputStream input = new CountingInputStream(
                new ByteArrayInputStream(new byte[65]));
        BasicHttpEntity entity = new BasicHttpEntity(
                input, 64, ContentType.APPLICATION_OCTET_STREAM);

        discarder.discard(entity, 64);

        assertEquals(64, input.bytesRead());
    }

    @Test
    void stopsAnUnknownLengthBodyAtTheCapWithoutReadingAProbeByte() {
        CountingInputStream input = new CountingInputStream(
                new ByteArrayInputStream(new byte[65]));
        BasicHttpEntity entity = new BasicHttpEntity(
                input, -1, ContentType.APPLICATION_OCTET_STREAM);

        assertThrows(
                ContentTooLongException.class,
                () -> discarder.discard(entity, 64));

        assertEquals(64, input.bytesRead());
    }

    @Test
    void rejectsAnOversizedDeclaredLengthBeforeOpeningTheBody() {
        ByteArrayEntity entity = new ByteArrayEntity(new byte[65], ContentType.APPLICATION_OCTET_STREAM);

        assertThrows(
                ContentTooLongException.class,
                () -> discarder.discard(entity, 64));
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

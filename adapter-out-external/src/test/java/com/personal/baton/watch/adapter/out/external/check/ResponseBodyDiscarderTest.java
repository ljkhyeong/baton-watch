package com.personal.baton.watch.adapter.out.external.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.BasicHttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.junit.jupiter.api.Test;

class ResponseBodyDiscarderTest {

    private final ResponseBodyDiscarder discarder = new ResponseBodyDiscarder();

    @Test
    void discardsAnExactLimitStreamingBodyWithoutRetainingIt() throws Exception {
        AtomicLong progress = new AtomicLong();
        BasicHttpEntity entity = new BasicHttpEntity(
                new ByteArrayInputStream(new byte[64]), -1, ContentType.APPLICATION_OCTET_STREAM);

        long consumed = discarder.discard(entity, 64, progress::set);

        assertEquals(64, consumed);
        assertEquals(64, progress.get());
    }

    @Test
    void stopsAStreamingBodyAtTheByteCap() {
        AtomicLong progress = new AtomicLong();
        BasicHttpEntity entity = new BasicHttpEntity(
                new ByteArrayInputStream(new byte[65]), -1, ContentType.APPLICATION_OCTET_STREAM);

        ResponseBodyDiscarder.ResponseTooLargeException failure = assertThrows(
                ResponseBodyDiscarder.ResponseTooLargeException.class,
                () -> discarder.discard(entity, 64, progress::set));

        assertEquals(64, failure.consumedWithinLimit());
        assertEquals(64, progress.get());
    }

    @Test
    void rejectsAnOversizedDeclaredLengthBeforeOpeningTheBody() {
        ByteArrayEntity entity = new ByteArrayEntity(new byte[65], ContentType.APPLICATION_OCTET_STREAM);

        ResponseBodyDiscarder.ResponseTooLargeException failure = assertThrows(
                ResponseBodyDiscarder.ResponseTooLargeException.class,
                () -> discarder.discard(entity, 64, ignored -> {}));

        assertEquals(0, failure.consumedWithinLimit());
    }
}

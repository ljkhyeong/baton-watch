package com.personal.baton.watch.adapter.out.external.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ContentTooLongException;
import org.apache.hc.core5.http.io.entity.BasicHttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.junit.jupiter.api.Test;

class ResponseBodyDiscarderTest {

    private final ResponseBodyDiscarder discarder = new ResponseBodyDiscarder();

    @Test
    void discardsAnUnknownLengthBodyBelowTheLimitWithoutRetainingIt() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[63]);
        BasicHttpEntity entity = new BasicHttpEntity(
                input, -1, ContentType.APPLICATION_OCTET_STREAM);

        discarder.discard(entity, 64);

        assertEquals(0, input.available());
    }

    @Test
    void acceptsAnExactDeclaredLengthWithoutReadingAProbeByte() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[65]);
        BasicHttpEntity entity = new BasicHttpEntity(
                input, 64, ContentType.APPLICATION_OCTET_STREAM);

        discarder.discard(entity, 64);

        assertEquals(1, input.available());
    }

    @Test
    void stopsAnUnknownLengthBodyAtTheCapWithoutReadingAProbeByte() {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[65]);
        BasicHttpEntity entity = new BasicHttpEntity(
                input, -1, ContentType.APPLICATION_OCTET_STREAM);

        assertThrows(
                ContentTooLongException.class,
                () -> discarder.discard(entity, 64));

        assertEquals(1, input.available());
    }

    @Test
    void rejectsAnOversizedDeclaredLengthBeforeOpeningTheBody() {
        ByteArrayEntity entity = new ByteArrayEntity(new byte[65], ContentType.APPLICATION_OCTET_STREAM);

        assertThrows(
                ContentTooLongException.class,
                () -> discarder.discard(entity, 64));
    }
}

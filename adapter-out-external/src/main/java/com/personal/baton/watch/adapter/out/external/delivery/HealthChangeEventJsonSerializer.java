package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

/** Serializes only the fixed BATON callback DTO; internal claim metadata is not representable here. */
final class HealthChangeEventJsonSerializer implements HealthChangeEventSerializer {

    private final ObjectWriter writer;

    HealthChangeEventJsonSerializer(ObjectMapper objectMapper) {
        writer = Objects.requireNonNull(objectMapper, "objectMapper")
                .writerFor(HealthChangeEventRequest.class)
                .without(SerializationFeature.INDENT_OUTPUT)
                .without(SerializationFeature.WRAP_ROOT_VALUE);
    }

    @Override
    public byte[] serialize(HealthChangeEventPayload payload) {
        try {
            return writer.writeValueAsBytes(HealthChangeEventRequest.from(payload));
        } catch (JacksonException ignored) {
            throw new IllegalStateException("health-change event serialization failed");
        }
    }
}

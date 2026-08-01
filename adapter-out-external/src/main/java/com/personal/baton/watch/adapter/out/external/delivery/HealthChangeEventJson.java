package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.application.monitoring.model.ClaimedHealthChangeEvent;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Serializes the fixed BATON callback contract without internal lease metadata. */
final class HealthChangeEventJson {

    private static final String EVENT_TYPE = "RESOURCE_HEALTH_CHANGED";

    byte[] serialize(ClaimedHealthChangeEvent event) {
        Objects.requireNonNull(event, "event");
        StringBuilder json = new StringBuilder(384);
        json.append('{');
        appendStringField(json, "eventId", event.eventId().toString());
        json.append(',');
        appendStringField(json, "eventType", EVENT_TYPE);
        json.append(',');
        appendStringField(json, "resourceReference", event.resourceReference().value());
        json.append(',');
        appendLongField(json, "sourceRevision", event.sourceRevision().value());
        event.attemptId().ifPresent(attemptId -> {
            json.append(',');
            appendStringField(json, "attemptId", attemptId.toString());
        });
        json.append(',');
        appendStringField(json, "previousHealth", event.previousHealth().name());
        json.append(',');
        appendStringField(json, "currentHealth", event.currentHealth().name());
        json.append(',');
        appendStringField(json, "changedAt", event.changedAt().toString());
        json.append('}');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendStringField(StringBuilder json, String name, String value) {
        appendQuoted(json, name);
        json.append(':');
        appendQuoted(json, value);
    }

    private static void appendLongField(StringBuilder json, String name, long value) {
        appendQuoted(json, name);
        json.append(':').append(value);
    }

    private static void appendQuoted(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append("\\u00");
                        int unsigned = character;
                        json.append(Character.forDigit((unsigned >>> 4) & 0x0f, 16));
                        json.append(Character.forDigit(unsigned & 0x0f, 16));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }
}

package com.personal.baton.watch.adapter.out.external.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.personal.baton.watch.application.monitoring.model.HealthChangeEventPayload;
import java.util.UUID;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 고정 BATON 콜백 DTO. 내부 점유 메타데이터는 이 표현에 포함할 수 없으며,
 * spring.jackson 전역 설정과 분리된 Jackson 기본 불변 매퍼로만 직렬화한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record HealthChangeEventRequest(
        String eventId,
        String eventType,
        String resourceReference,
        long sourceRevision,
        String attemptId,
        String previousHealth,
        String currentHealth,
        String changedAt) {

    private static final String EVENT_TYPE = "RESOURCE_HEALTH_CHANGED";
    private static final ObjectWriter WRITER = JsonMapper.shared().writerFor(HealthChangeEventRequest.class);

    static byte[] serialize(HealthChangeEventPayload payload) {
        return WRITER.writeValueAsBytes(from(payload));
    }

    private static HealthChangeEventRequest from(HealthChangeEventPayload payload) {
        return new HealthChangeEventRequest(
                payload.eventId().toString(),
                EVENT_TYPE,
                payload.resourceReference().value(),
                payload.sourceRevision().value(),
                payload.attemptId().map(UUID::toString).orElse(null),
                payload.previousHealth().name(),
                payload.currentHealth().name(),
                payload.changedAt().toString());
    }
}

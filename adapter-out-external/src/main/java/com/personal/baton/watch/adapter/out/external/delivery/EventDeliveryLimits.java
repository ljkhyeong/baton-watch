package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.time.Duration;

/** 단일 상태 변경 이벤트 전달 시도의 런타임 제한. */
public record EventDeliveryLimits(
        Duration connectTimeout,
        Duration responseTimeout,
        Duration totalTimeout,
        long maxResponseBytes,
        int maxHeaderCount,
        int maxHeaderLineLength) {

    public EventDeliveryLimits {
        OutboundResourceBounds.requirePositiveDuration(connectTimeout, "connectTimeout");
        OutboundResourceBounds.requirePositiveDuration(responseTimeout, "responseTimeout");
        OutboundResourceBounds.requirePositiveDuration(totalTimeout, "totalTimeout");
        OutboundResourceBounds.requireNanosRepresentable(totalTimeout, "totalTimeout");
        OutboundResourceBounds.requireResponseBytes(
                maxResponseBytes, OutboundResourceBounds.MAX_EVENT_DELIVERY_RESPONSE_BYTES);
        OutboundResourceBounds.requireHeaderBounds(maxHeaderCount, maxHeaderLineLength);
    }
}

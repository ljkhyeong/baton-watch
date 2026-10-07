package com.personal.baton.watch.adapter.out.external.delivery;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import com.personal.baton.watch.adapter.out.external.http.ApacheHttpClientLimits;
import java.time.Duration;
import org.apache.hc.core5.util.Args;

/** 단일 상태 변경 이벤트 전달 시도의 런타임 제한. */
public record EventDeliveryLimits(
        Duration connectTimeout,
        Duration responseTimeout,
        Duration totalTimeout,
        long maxResponseBytes,
        int maxHeaderCount,
        int maxHeaderLineLength) implements ApacheHttpClientLimits {

    public EventDeliveryLimits {
        OutboundResourceBounds.requirePositiveDuration(connectTimeout, "connectTimeout");
        OutboundResourceBounds.requirePositiveDuration(responseTimeout, "responseTimeout");
        OutboundResourceBounds.requirePositiveDuration(totalTimeout, "totalTimeout");
        OutboundResourceBounds.requireNanosRepresentable(totalTimeout, "totalTimeout");
        Args.checkRange(
                maxResponseBytes, 1, OutboundResourceBounds.MAX_EVENT_DELIVERY_RESPONSE_BYTES, "maxResponseBytes");
        OutboundResourceBounds.requireHeaderBounds(maxHeaderCount, maxHeaderLineLength);
    }
}

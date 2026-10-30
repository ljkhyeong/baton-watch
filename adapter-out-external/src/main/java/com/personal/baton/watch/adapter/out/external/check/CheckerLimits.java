package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import com.personal.baton.watch.adapter.out.external.http.ApacheHttpClientLimits;
import java.time.Duration;

/** 단일 아웃바운드 URL 점검의 런타임 제한. 어떤 제한도 비활성화할 수 없다. */
public record CheckerLimits(
        Duration connectTimeout,
        Duration responseTimeout,
        Duration totalTimeout,
        int maxHeaderCount,
        int maxHeaderLineLength) implements ApacheHttpClientLimits {

    public CheckerLimits {
        OutboundResourceBounds.requirePositiveDuration(connectTimeout, "connectTimeout");
        OutboundResourceBounds.requirePositiveDuration(responseTimeout, "responseTimeout");
        OutboundResourceBounds.requirePositiveDuration(totalTimeout, "totalTimeout");
        OutboundResourceBounds.requireNanosRepresentable(totalTimeout, "totalTimeout");
        OutboundResourceBounds.requireHeaderBounds(maxHeaderCount, maxHeaderLineLength);
    }
}

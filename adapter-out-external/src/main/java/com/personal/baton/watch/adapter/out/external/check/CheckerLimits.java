package com.personal.baton.watch.adapter.out.external.check;

import com.personal.baton.watch.adapter.out.external.OutboundResourceBounds;
import java.time.Duration;
import org.apache.hc.core5.util.Args;

/** 단일 아웃바운드 URL 점검의 런타임 제한. 어떤 제한도 비활성화할 수 없다. */
public record CheckerLimits(
        Duration connectTimeout,
        Duration responseTimeout,
        Duration totalTimeout,
        int maxRedirects,
        int maxHeaderCount,
        int maxHeaderLineLength) {

    public CheckerLimits {
        OutboundResourceBounds.requirePositiveDuration(connectTimeout, "connectTimeout");
        OutboundResourceBounds.requirePositiveDuration(responseTimeout, "responseTimeout");
        OutboundResourceBounds.requirePositiveDuration(totalTimeout, "totalTimeout");
        OutboundResourceBounds.requireNanosRepresentable(totalTimeout, "totalTimeout");
        Args.checkRange(maxRedirects, 0, 3, "maxRedirects");
        OutboundResourceBounds.requireHeaderBounds(maxHeaderCount, maxHeaderLineLength);
    }
}

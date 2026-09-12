package com.personal.baton.watch.adapter.in.web.monitoring;

import com.personal.baton.watch.domain.monitoring.MonitorProjection;
import com.personal.baton.watch.domain.monitoring.ResourceReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public record MonitorBatchResponse(List<MonitorResponse> monitors, List<String> missingResourceReferences) {

    public static MonitorBatchResponse from(
            List<ResourceReference> requested, List<MonitorProjection> projections, Instant observedAt) {
        var byReference = projections.stream()
                .collect(Collectors.toMap(MonitorProjection::resourceReference, Function.identity()));
        List<MonitorResponse> monitors = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (ResourceReference reference : new LinkedHashSet<>(requested)) {
            MonitorProjection projection = byReference.get(reference);
            if (projection == null) {
                missing.add(reference.value());
            } else {
                monitors.add(MonitorResponse.from(projection, observedAt));
            }
        }
        return new MonitorBatchResponse(List.copyOf(monitors), List.copyOf(missing));
    }
}

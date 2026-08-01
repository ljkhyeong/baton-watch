package com.personal.baton.watch.application.monitoring.port.out;

import com.personal.baton.watch.application.monitoring.model.CheckObservation;
import com.personal.baton.watch.domain.monitoring.TargetUrl;

@FunctionalInterface
public interface UrlChecker {

    CheckObservation check(TargetUrl targetUrl);
}

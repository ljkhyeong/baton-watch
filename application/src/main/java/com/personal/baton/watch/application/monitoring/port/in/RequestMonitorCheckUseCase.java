package com.personal.baton.watch.application.monitoring.port.in;

import com.personal.baton.watch.application.monitoring.model.MonitorCheckRequestResult;
import com.personal.baton.watch.domain.monitoring.ResourceReference;

public interface RequestMonitorCheckUseCase {
    MonitorCheckRequestResult requestCheck(ResourceReference resourceReference);
}

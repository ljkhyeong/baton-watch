package com.personal.baton.watch.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ErrorHandler;

final class RedactingScheduledTaskErrorHandler implements ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(RedactingScheduledTaskErrorHandler.class);

    @Override
    public void handleError(Throwable failure) {
        log.error("scheduled task failed failureType={}", failure.getClass().getSimpleName());
    }
}

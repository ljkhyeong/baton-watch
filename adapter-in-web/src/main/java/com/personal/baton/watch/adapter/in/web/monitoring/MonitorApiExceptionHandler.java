package com.personal.baton.watch.adapter.in.web.monitoring;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ResourceMonitorController.class)
public final class MonitorApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MonitorApiExceptionHandler.class);
    private static final URI REDACTED_REQUEST = URI.create("urn:baton-watch:request");

    @ExceptionHandler(MonitorApiException.class)
    ResponseEntity<ProblemDetail> handleMonitorApiException(MonitorApiException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(exception.status());
        problem.setType(exception.type());
        problem.setTitle(exception.title());
        problem.setInstance(REDACTED_REQUEST);
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(exception.status()).body(problem);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> handleInvalidRequest(Exception ignored) {
        return handleMonitorApiException(MonitorApiException.invalidRequest());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        log.error("monitor API failed failureType={}", exception.getClass().getSimpleName());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setType(URI.create("urn:baton-watch:problem:internal-error"));
        problem.setTitle("Internal server error");
        problem.setInstance(REDACTED_REQUEST);
        problem.setProperty("code", "INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}

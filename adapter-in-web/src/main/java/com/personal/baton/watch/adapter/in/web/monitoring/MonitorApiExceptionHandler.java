package com.personal.baton.watch.adapter.in.web.monitoring;

import com.personal.baton.watch.adapter.in.web.MonitorApiProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public final class MonitorApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MonitorApiExceptionHandler.class);
    private static final MonitorApiProblem INVALID_REQUEST =
            MonitorApiProblem.of("invalid-request", "Invalid request", "INVALID_REQUEST");
    private static final MonitorApiProblem ROUTE_NOT_FOUND =
            MonitorApiProblem.of("route-not-found", "Route not found", "ROUTE_NOT_FOUND");
    private static final MonitorApiProblem METHOD_NOT_ALLOWED =
            MonitorApiProblem.of("method-not-allowed", "Method not allowed", "METHOD_NOT_ALLOWED");
    private static final MonitorApiProblem NOT_ACCEPTABLE =
            MonitorApiProblem.of("not-acceptable", "Not acceptable", "NOT_ACCEPTABLE");
    private static final MonitorApiProblem UNSUPPORTED_MEDIA_TYPE = MonitorApiProblem.of(
            "unsupported-media-type",
            "Unsupported media type",
            "UNSUPPORTED_MEDIA_TYPE");
    private static final MonitorApiProblem REQUEST_REJECTED =
            MonitorApiProblem.of("request-rejected", "Request rejected", "REQUEST_REJECTED");
    private static final MonitorApiProblem INTERNAL_ERROR =
            MonitorApiProblem.of("internal-error", "Internal server error", "INTERNAL_ERROR");

    @ExceptionHandler(MonitorApiException.class)
    ResponseEntity<Object> handleMonitorApiException(MonitorApiException exception) {
        HttpHeaders headers = new HttpHeaders();
        if (exception.retryAfterSeconds() > 0) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()));
        }
        return problem(
                exception.status(),
                exception.problem(),
                headers);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception exception) {
        logFailure(exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, HttpHeaders.EMPTY);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest
                && servletRequest.getResponse() != null
                && servletRequest.getResponse().isCommitted()) {
            if (!status.is4xxClientError()) {
                logFailure(exception);
            }
            return null;
        }
        if (!status.is4xxClientError()) {
            logFailure(exception);
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, headers);
        }
        MonitorApiProblem problem = switch (status.value()) {
            case 400 -> INVALID_REQUEST;
            case 404 -> ROUTE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> REQUEST_REJECTED;
        };
        return problem(status, problem, headers);
    }

    private ResponseEntity<Object> problem(
            HttpStatusCode status,
            MonitorApiProblem spec,
            HttpHeaders headers) {
        HttpHeaders responseHeaders = HttpHeaders.copyOf(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(spec.toProblemDetail(status, true), responseHeaders, status);
    }

    private void logFailure(Exception exception) {
        log.error("모니터 API 처리 실패 failureType={}", exception.getClass().getSimpleName());
    }

}

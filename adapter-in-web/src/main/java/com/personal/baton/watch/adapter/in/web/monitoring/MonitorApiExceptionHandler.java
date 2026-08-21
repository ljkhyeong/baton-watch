package com.personal.baton.watch.adapter.in.web.monitoring;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public final class MonitorApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MonitorApiExceptionHandler.class);
    private static final URI REDACTED_REQUEST = URI.create("urn:baton-watch:request");
    private static final ProblemSpec INVALID_REQUEST =
            ProblemSpec.of("invalid-request", "Invalid request", "INVALID_REQUEST");
    private static final ProblemSpec ROUTE_NOT_FOUND =
            ProblemSpec.of("route-not-found", "Route not found", "ROUTE_NOT_FOUND");
    private static final ProblemSpec METHOD_NOT_ALLOWED =
            ProblemSpec.of("method-not-allowed", "Method not allowed", "METHOD_NOT_ALLOWED");
    private static final ProblemSpec NOT_ACCEPTABLE =
            ProblemSpec.of("not-acceptable", "Not acceptable", "NOT_ACCEPTABLE");
    private static final ProblemSpec UNSUPPORTED_MEDIA_TYPE = ProblemSpec.of(
            "unsupported-media-type",
            "Unsupported media type",
            "UNSUPPORTED_MEDIA_TYPE");
    private static final ProblemSpec REQUEST_REJECTED =
            ProblemSpec.of("request-rejected", "Request rejected", "REQUEST_REJECTED");
    private static final ProblemSpec INTERNAL_ERROR =
            ProblemSpec.of("internal-error", "Internal server error", "INTERNAL_ERROR");

    @ExceptionHandler(MonitorApiException.class)
    ResponseEntity<Object> handleMonitorApiException(MonitorApiException exception) {
        return problem(
                exception.status(),
                new ProblemSpec(exception.type(), exception.getMessage(), exception.code()),
                HttpHeaders.EMPTY);
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
        if (responseCommitted(request)) {
            if (!status.is4xxClientError()) {
                logFailure(exception);
            }
            return null;
        }
        if (!status.is4xxClientError()) {
            logFailure(exception);
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, headers);
        }
        ProblemSpec problem = switch (status.value()) {
            case 400 -> INVALID_REQUEST;
            case 404 -> ROUTE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> REQUEST_REJECTED;
        };
        return problem(status, problem, headers);
    }

    private boolean responseCommitted(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                && servletRequest.getResponse() != null
                && servletRequest.getResponse().isCommitted();
    }

    private ResponseEntity<Object> problem(
            HttpStatusCode status,
            ProblemSpec spec,
            HttpHeaders headers) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(spec.type());
        problem.setTitle(spec.title());
        problem.setInstance(REDACTED_REQUEST);
        problem.setProperty("code", spec.code());
        HttpHeaders responseHeaders = HttpHeaders.copyOf(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, responseHeaders, status);
    }

    private void logFailure(Exception exception) {
        log.error("monitor API failed failureType={}", exception.getClass().getSimpleName());
    }

    private record ProblemSpec(URI type, String title, String code) {

        private static ProblemSpec of(String slug, String title, String code) {
            return new ProblemSpec(
                    URI.create("urn:baton-watch:problem:" + slug),
                    title,
                    code);
        }
    }
}

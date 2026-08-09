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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public final class MonitorApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MonitorApiExceptionHandler.class);
    private static final URI REDACTED_REQUEST = URI.create("urn:baton-watch:request");
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
                exception.type(),
                exception.title(),
                exception.code(),
                HttpHeaders.EMPTY);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return invalidRequest(exception, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return invalidRequest(exception, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return frameworkProblem(exception, request, status, ROUTE_NOT_FOUND, headers);
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(
            NoHandlerFoundException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return frameworkProblem(exception, request, status, ROUTE_NOT_FOUND, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return frameworkProblem(exception, request, status, METHOD_NOT_ALLOWED, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return frameworkProblem(exception, request, status, UNSUPPORTED_MEDIA_TYPE, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return frameworkProblem(exception, request, status, NOT_ACCEPTABLE, headers);
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
        if (!status.is4xxClientError()) {
            logFailure(exception);
            return frameworkProblem(
                    exception,
                    request,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    INTERNAL_ERROR,
                    headers);
        }
        if (status.value() == HttpStatus.BAD_REQUEST.value()) {
            MonitorApiException invalidRequest = MonitorApiException.invalidRequest();
            return frameworkProblem(
                    exception,
                    request,
                    status,
                    invalidRequest.type(),
                    invalidRequest.title(),
                    invalidRequest.code(),
                    headers);
        }
        return frameworkProblem(exception, request, status, REQUEST_REJECTED, headers);
    }

    private ResponseEntity<Object> invalidRequest(
            Exception exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        MonitorApiException invalidRequest = MonitorApiException.invalidRequest();
        return frameworkProblem(
                exception,
                request,
                status,
                invalidRequest.type(),
                invalidRequest.title(),
                invalidRequest.code(),
                headers);
    }

    private ResponseEntity<Object> frameworkProblem(
            Exception exception,
            WebRequest request,
            HttpStatusCode status,
            ProblemSpec problem,
            HttpHeaders headers) {
        return super.handleExceptionInternal(
                exception,
                problemDetail(status, problem),
                problemHeaders(headers),
                status,
                request);
    }

    private ResponseEntity<Object> frameworkProblem(
            Exception exception,
            WebRequest request,
            HttpStatusCode status,
            URI type,
            String title,
            String code,
            HttpHeaders headers) {
        return frameworkProblem(
                exception,
                request,
                status,
                new ProblemSpec(type, title, code),
                headers);
    }

    private ResponseEntity<Object> problem(
            HttpStatusCode status,
            ProblemSpec problem,
            HttpHeaders headers) {
        return new ResponseEntity<>(
                problemDetail(status, problem),
                problemHeaders(headers),
                status);
    }

    private ResponseEntity<Object> problem(
            HttpStatusCode status,
            URI type,
            String title,
            String code,
            HttpHeaders headers) {
        return problem(status, new ProblemSpec(type, title, code), headers);
    }

    private ProblemDetail problemDetail(HttpStatusCode status, ProblemSpec spec) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(spec.type());
        problem.setTitle(spec.title());
        problem.setInstance(REDACTED_REQUEST);
        problem.setProperty("code", spec.code());
        return problem;
    }

    private HttpHeaders problemHeaders(HttpHeaders headers) {
        HttpHeaders responseHeaders = HttpHeaders.copyOf(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return responseHeaders;
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

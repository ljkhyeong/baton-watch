package com.personal.baton.watch.adapter.in.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** 인증된 모니터 동기화 본문을 Jackson 객체화 전에 제한된 크기로 버퍼링한다. */
public final class MonitorApiRequestBodyLimitFilter extends OncePerRequestFilter {

    // 최대 2,048자 URL을 모든 문자가 JSON Unicode escape인 경우에도 수용할 여유를 둔다.
    public static final int MAX_REQUEST_BODY_BYTES = 16 * 1024;

    private static final PathPatternRequestMatcher MONITOR_SYNCHRONIZATION =
            PathPatternRequestMatcher.pathPattern(
                    HttpMethod.PUT, "/api/v1/resource-monitors/{resourceReference}");

    private final ObjectMapper objectMapper;

    public MonitorApiRequestBodyLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!MONITOR_SYNCHRONIZATION.matches(request) || !hasJsonContentType(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_REQUEST_BODY_BYTES) {
            reject(response);
            return;
        }

        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (body.length > MAX_REQUEST_BODY_BYTES) {
            reject(response);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static boolean hasJsonContentType(HttpServletRequest request) {
        String value = request.getContentType();
        if (value == null) {
            return false;
        }
        try {
            return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(value));
        } catch (InvalidMediaTypeException exception) {
            return false;
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        MonitorApiProblemWriter.write(
                objectMapper,
                response,
                HttpStatus.CONTENT_TOO_LARGE,
                "payload-too-large",
                "Payload too large",
                "PAYLOAD_TOO_LARGE",
                true);
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ByteArrayServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        private ByteArrayServletInputStream(byte[] body) {
            delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            try {
                if (!isFinished()) {
                    readListener.onDataAvailable();
                }
                if (isFinished()) {
                    readListener.onAllDataRead();
                }
            } catch (IOException exception) {
                readListener.onError(exception);
            }
        }
    }
}

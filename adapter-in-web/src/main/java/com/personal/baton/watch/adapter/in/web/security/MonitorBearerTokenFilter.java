package com.personal.baton.watch.adapter.in.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public final class MonitorBearerTokenFilter extends OncePerRequestFilter {

    private static final String MONITOR_ROUTE_PREFIX = "/api/v1/resource-monitors";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTHORIZED_PROBLEM = """
            {"type":"urn:baton-watch:problem:unauthorized","title":"Unauthorized","status":401,"code":"UNAUTHORIZED"}
            """.trim();

    private final byte[] expectedToken;

    public MonitorBearerTokenFilter(String expectedToken) {
        Objects.requireNonNull(expectedToken, "expectedToken");
        if (expectedToken.isBlank()) {
            throw new IllegalArgumentException("expectedToken must not be blank");
        }
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals(MONITOR_ROUTE_PREFIX) || path.startsWith(MONITOR_ROUTE_PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (!hasExpectedToken(authorization)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(UNAUTHORIZED_PROBLEM);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean hasExpectedToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }
        byte[] suppliedToken = authorization.substring(BEARER_PREFIX.length())
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, suppliedToken);
    }
}

package com.personal.baton.watch.adapter.in.web.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/** 운영자가 설정한 모니터 API 서비스 토큰을 인증한다. */
public final class MonitorBearerTokenAuthenticationManager implements AuthenticationManager {

    private static final String PRINCIPAL = "baton-watch-monitor-api";

    private final byte[] expectedToken;

    public MonitorBearerTokenAuthenticationManager(String expectedToken) {
        Objects.requireNonNull(expectedToken, "expectedToken");
        if (expectedToken.isBlank()) {
            throw new IllegalArgumentException("expectedToken must not be blank");
        }
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        if (!(authentication instanceof BearerTokenAuthenticationToken bearerToken)
                || !MessageDigest.isEqual(
                        expectedToken,
                        bearerToken.getToken().getBytes(StandardCharsets.UTF_8))) {
            throw new BadCredentialsException("invalid monitor API credentials");
        }
        return new PreAuthenticatedAuthenticationToken(
                PRINCIPAL, null, AuthorityUtils.NO_AUTHORITIES);
    }
}

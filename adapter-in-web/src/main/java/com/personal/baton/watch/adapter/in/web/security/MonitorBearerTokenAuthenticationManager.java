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

/** 운영자가 설정한 모니터 API 서비스 토큰을 인증한다. 토큰 교체 기간에는 이전 토큰도 허용한다. */
public final class MonitorBearerTokenAuthenticationManager implements AuthenticationManager {

    private static final String PRINCIPAL = "baton-watch-monitor-api";

    private final byte[] currentToken;
    private final byte[] previousToken;
    private final Runnable previousTokenUsed;

    /** 교체 기간이 아니면 {@code previousToken}은 {@code null}이다. 이전 토큰으로 인증할 때마다 알림을 호출한다. */
    public MonitorBearerTokenAuthenticationManager(
            String currentToken, String previousToken, Runnable previousTokenUsed) {
        this.currentToken = currentToken.getBytes(StandardCharsets.UTF_8);
        this.previousToken = previousToken == null ? null : previousToken.getBytes(StandardCharsets.UTF_8);
        this.previousTokenUsed = Objects.requireNonNull(previousTokenUsed, "previousTokenUsed");
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        if (!(authentication instanceof BearerTokenAuthenticationToken bearerToken)) {
            throw invalidCredentials();
        }
        byte[] presented = bearerToken.getToken().getBytes(StandardCharsets.UTF_8);
        // 어느 토큰과 일치했는지 응답 시간으로 드러나지 않도록 두 토큰을 모두 비교한다.
        boolean current = MessageDigest.isEqual(currentToken, presented);
        boolean previous = previousToken != null && MessageDigest.isEqual(previousToken, presented);
        if (!current && !previous) {
            throw invalidCredentials();
        }
        if (previous) {
            previousTokenUsed.run();
        }
        return new PreAuthenticatedAuthenticationToken(
                PRINCIPAL, null, AuthorityUtils.NO_AUTHORITIES);
    }

    private static BadCredentialsException invalidCredentials() {
        return new BadCredentialsException("invalid monitor API credentials");
    }
}

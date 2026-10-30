package com.personal.baton.watch.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

class MonitorBearerTokenAuthenticationManagerTest {

    private static final String EXPECTED_TOKEN = "monitor-api-token-0123456789-abcdef";
    private static final String PREVIOUS_TOKEN = "previous-monitor-api-token-0123456789";

    private final AtomicInteger previousTokenUses = new AtomicInteger();
    private final MonitorBearerTokenAuthenticationManager manager =
            new MonitorBearerTokenAuthenticationManager(EXPECTED_TOKEN, null, previousTokenUses::incrementAndGet);

    @Test
    void authenticatesWithoutRetainingTheServiceToken() {
        Authentication authenticated = manager.authenticate(
                new BearerTokenAuthenticationToken(EXPECTED_TOKEN));

        assertThat(authenticated.isAuthenticated()).isTrue();
        assertThat(authenticated.getPrincipal()).isEqualTo("baton-watch-monitor-api");
        assertThat(authenticated.getCredentials()).isNull();
        assertThat(authenticated.getAuthorities()).isEmpty();
        assertThat(previousTokenUses).hasValue(0);
    }

    @Test
    void rejectsWrongOrUnexpectedAuthenticationWithoutExposingTheToken() {
        assertThatThrownBy(() -> manager.authenticate(new BearerTokenAuthenticationToken("wrong-token")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid monitor API credentials");
        assertThatThrownBy(() -> manager.authenticate(
                        new TestingAuthenticationToken("principal", "unexpected-secret")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid monitor API credentials");
    }

    @Test
    void acceptsBothTokensDuringRotationAndReportsOnlyPreviousTokenUse() {
        var rotating = new MonitorBearerTokenAuthenticationManager(
                EXPECTED_TOKEN, PREVIOUS_TOKEN, previousTokenUses::incrementAndGet);

        Authentication current = rotating.authenticate(new BearerTokenAuthenticationToken(EXPECTED_TOKEN));
        assertThat(previousTokenUses).hasValue(0);
        Authentication previous = rotating.authenticate(new BearerTokenAuthenticationToken(PREVIOUS_TOKEN));

        assertThat(previousTokenUses).hasValue(1);
        assertThat(previous.getPrincipal()).isEqualTo(current.getPrincipal());
        assertThat(previous.getCredentials()).isNull();
        assertThatThrownBy(() -> rotating.authenticate(new BearerTokenAuthenticationToken("wrong-token")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid monitor API credentials");
        assertThat(previousTokenUses).hasValue(1);
    }
}

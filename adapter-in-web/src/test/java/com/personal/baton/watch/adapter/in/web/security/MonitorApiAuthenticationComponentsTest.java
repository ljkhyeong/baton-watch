package com.personal.baton.watch.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

class MonitorApiAuthenticationComponentsTest {

    private static final String EXPECTED_TOKEN = "monitor-api-token-0123456789-abcdef";

    private final MonitorBearerTokenAuthenticationConverter converter =
            new MonitorBearerTokenAuthenticationConverter();
    private final MonitorBearerTokenAuthenticationManager manager =
            new MonitorBearerTokenAuthenticationManager(EXPECTED_TOKEN);

    @Test
    void convertsOnlyBearerAuthorizationHeadersWithACaseInsensitiveScheme() {
        MockHttpServletRequest valid = new MockHttpServletRequest();
        valid.addHeader("Authorization", "Bearer " + EXPECTED_TOKEN);
        MockHttpServletRequest basic = new MockHttpServletRequest();
        basic.addHeader("Authorization", "Basic " + EXPECTED_TOKEN);
        MockHttpServletRequest lowercase = new MockHttpServletRequest();
        lowercase.addHeader("Authorization", "bearer " + EXPECTED_TOKEN);
        MockHttpServletRequest blank = new MockHttpServletRequest();
        blank.addHeader("Authorization", "Bearer    ");

        assertThat(converter.convert(new MockHttpServletRequest())).isNull();
        assertThat(converter.convert(basic)).isNull();
        assertThat(converter.convert(blank)).isNull();
        assertThat(converter.convert(valid))
                .isInstanceOf(BearerTokenAuthenticationToken.class)
                .extracting(Authentication::getCredentials)
                .isEqualTo(EXPECTED_TOKEN);
        assertThat(converter.convert(lowercase))
                .isInstanceOf(BearerTokenAuthenticationToken.class)
                .extracting(Authentication::getCredentials)
                .isEqualTo(EXPECTED_TOKEN);
    }

    @Test
    void authenticatesWithoutRetainingTheServiceToken() {
        Authentication authenticated = manager.authenticate(
                new BearerTokenAuthenticationToken(EXPECTED_TOKEN));

        assertThat(authenticated.isAuthenticated()).isTrue();
        assertThat(authenticated.getPrincipal()).isEqualTo("baton-watch-monitor-api");
        assertThat(authenticated.getCredentials()).isNull();
        assertThat(authenticated.getAuthorities()).isEmpty();
    }

    @Test
    void rejectsWrongOrUnexpectedAuthenticationWithoutExposingTheToken() {
        assertThatThrownBy(() -> manager.authenticate(new BearerTokenAuthenticationToken("wrong-token")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid monitor API credentials")
                .hasMessageNotContaining("wrong-token");
        assertThatThrownBy(() -> manager.authenticate(
                        new TestingAuthenticationToken("principal", "unexpected-secret")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("invalid monitor API credentials")
                .hasMessageNotContaining("unexpected-secret");
    }
}

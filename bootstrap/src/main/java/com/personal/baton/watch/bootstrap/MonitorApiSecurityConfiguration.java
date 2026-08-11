package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.adapter.in.web.security.MonitorApiAuthenticationEntryPoint;
import com.personal.baton.watch.adapter.in.web.security.MonitorApiRequestRejectedHandler;
import com.personal.baton.watch.adapter.in.web.security.MonitorBearerTokenAuthenticationConverter;
import com.personal.baton.watch.adapter.in.web.security.MonitorBearerTokenAuthenticationManager;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.CompositeRequestRejectedHandler;
import org.springframework.security.web.firewall.ObservationMarkingRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class MonitorApiSecurityConfiguration {

    private static final PathPatternRequestMatcher PUBLIC_SYSTEM_STATUS =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/system/status");
    private static final PathPatternRequestMatcher VERSIONED_API =
            PathPatternRequestMatcher.pathPattern("/api/v1/**");

    @Bean
    MonitorBearerTokenAuthenticationConverter monitorBearerTokenAuthenticationConverter() {
        return new MonitorBearerTokenAuthenticationConverter();
    }

    @Bean
    MonitorBearerTokenAuthenticationManager monitorBearerTokenAuthenticationManager(WatchProperties properties) {
        return new MonitorBearerTokenAuthenticationManager(properties.apiToken());
    }

    @Bean
    MonitorApiAuthenticationEntryPoint monitorApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new MonitorApiAuthenticationEntryPoint(objectMapper);
    }

    @Bean
    RequestRejectedHandler monitorApiRequestRejectedHandler(
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry) {
        return new CompositeRequestRejectedHandler(
                new ObservationMarkingRequestRejectedHandler(observationRegistry),
                new MonitorApiRequestRejectedHandler(objectMapper));
    }

    @Bean
    @Order(1)
    SecurityFilterChain publicSystemStatusSecurityFilterChain(HttpSecurity http) throws Exception {
        return stateless(http)
                .securityMatcher(PUBLIC_SYSTEM_STATUS)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain versionedApiSecurityFilterChain(
            HttpSecurity http,
            MonitorBearerTokenAuthenticationConverter converter,
            MonitorBearerTokenAuthenticationManager authenticationManager,
            MonitorApiAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        return stateless(http)
                .securityMatcher(VERSIONED_API)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(authenticationEntryPoint))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationManagerResolver(request -> authenticationManager)
                        .authenticationConverter(converter)
                        .authenticationEntryPoint(authenticationEntryPoint))
                .build();
    }

    private static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
    }
}

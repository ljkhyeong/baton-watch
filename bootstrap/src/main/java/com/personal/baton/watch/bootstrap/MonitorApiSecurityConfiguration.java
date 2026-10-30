package com.personal.baton.watch.bootstrap;

import com.personal.baton.watch.adapter.in.web.security.MonitorApiAuthenticationEntryPoint;
import com.personal.baton.watch.adapter.in.web.security.MonitorApiRequestRejectedHandler;
import com.personal.baton.watch.adapter.in.web.security.MonitorBearerTokenAuthenticationManager;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class MonitorApiSecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MonitorApiSecurityConfiguration.class);
    private static final RequestMatcher PUBLIC_SYSTEM_STATUS = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/system/status"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.HEAD, "/api/v1/system/status"));
    private static final PathPatternRequestMatcher VERSIONED_API =
            PathPatternRequestMatcher.pathPattern("/api/v1/**");

    @Bean
    MonitorBearerTokenAuthenticationManager monitorBearerTokenAuthenticationManager(
            WatchProperties properties, MonitoringMetrics metrics) {
        if (properties.apiPreviousToken() != null) {
            log.info("모니터 API 이전 토큰을 함께 허용합니다. BATON이 새 토큰으로 바뀌면 제거하세요");
        }
        return new MonitorBearerTokenAuthenticationManager(
                properties.apiToken(),
                properties.apiPreviousToken(),
                metrics::recordPreviousApiTokenAuthentication);
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
            MonitorBearerTokenAuthenticationManager authenticationManager,
            ObjectMapper objectMapper) throws Exception {
        return stateless(http)
                .securityMatcher(VERSIONED_API)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationManagerResolver(request -> authenticationManager)
                        .authenticationEntryPoint(new MonitorApiAuthenticationEntryPoint(objectMapper)))
                .build();
    }

    private static HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }
}

package com.storeanalytics.integration.livesklad.webhook;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration(proxyBeanMethods = false)
class LiveSkladWebhookSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain liveSkladWebhookSecurityFilterChain(
            HttpSecurity http,
            LiveSkladWebhookProperties properties
    ) throws Exception {
        http
                .securityMatcher("/api/integrations/livesklad/webhooks/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS
                ))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .addFilterBefore(
                        new LiveSkladWebhookAuthenticationFilter(properties),
                        AuthorizationFilter.class
                );
        return http.build();
    }
}

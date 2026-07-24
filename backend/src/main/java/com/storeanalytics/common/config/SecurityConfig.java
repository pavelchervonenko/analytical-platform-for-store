package com.storeanalytics.common.config;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityChainComponents components,
            CsrfTokenRepository csrfTokenRepository,
            SessionRegistry sessionRegistry,
            ApplicationSecurityProperties properties
    ) throws Exception {
        AuthorizationManager<RequestAuthorizationContext> adminAccess = AuthorizationManagers.allOf(
                AuthorityAuthorizationManager.hasRole("ADMIN"),
                components.passwordChanged()
        );

        http
                .cors(cors -> { })
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(components.authenticationEntryPoint())
                        .accessDeniedHandler(components.accessDeniedHandler())
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/csrf", "/api/auth/login").permitAll()
                        .requestMatchers(
                                "/api/auth/me", "/api/auth/change-password", "/api/auth/logout"
                        ).authenticated()
                        .requestMatchers(
                                "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
                                "/actuator/metrics/**"
                        ).access(adminAccess)
                        .requestMatchers("/api/admin/**", "/api/sync/**").access(adminAccess)
                        .requestMatchers(
                                "/api/integration-connections/*/product-category-imports"
                        ).access(adminAccess)
                        .requestMatchers("/api/stores/**", "/api/data-quality/**", "/api/system/status")
                        .access(components.passwordChanged())
                        .anyRequest().denyAll()
                )
                .sessionManagement(session -> session
                        .maximumSessions(properties.maxConcurrentSessions())
                        .maxSessionsPreventsLogin(false)
                        .sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(components.expiredSessionStrategy())
                )
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value()))
                )
                .addFilterAfter(
                        components.userSecurityStateFilter(),
                        SecurityContextHolderFilter.class
                );

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder(
                "bcrypt",
                java.util.Map.of("bcrypt", new BCryptPasswordEncoder(12))
        );
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository(ApplicationSecurityProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie
                .secure(properties.secureCookies())
                .sameSite("Lax")
                .path("/"));
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(ApplicationSecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(validateOrigins(properties.corsAllowedOrigins()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Content-Type", "X-XSRF-TOKEN", "X-Correlation-ID"
        ));
        configuration.setExposedHeaders(List.of(
                "X-XSRF-TOKEN", "X-Correlation-ID"
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private List<String> validateOrigins(List<String> configuredOrigins) {
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        for (String configuredOrigin : configuredOrigins) {
            String origin = configuredOrigin == null ? "" : configuredOrigin.trim();
            if (origin.isEmpty() || origin.contains("*")) {
                throw new IllegalArgumentException("CORS origins must be explicit");
            }
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("CORS origin is invalid", exception);
            }
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())) {
                throw new IllegalArgumentException("CORS origin must contain only scheme and authority");
            }
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
                throw new IllegalArgumentException("CORS origin must use HTTP or HTTPS");
            }
            origins.add(origin);
        }
        if (origins.isEmpty()) {
            throw new IllegalArgumentException("At least one CORS origin must be configured");
        }
        return List.copyOf(origins);
    }
}

package com.storeanalytics.common.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record ApplicationSecurityProperties(
        List<String> corsAllowedOrigins,
        boolean secureCookies,
        Duration sessionAbsoluteTimeout,
        int maxConcurrentSessions
) {

    public ApplicationSecurityProperties {
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
        if (sessionAbsoluteTimeout == null
                || sessionAbsoluteTimeout.isZero()
                || sessionAbsoluteTimeout.isNegative()) {
            throw new IllegalArgumentException("Session absolute timeout must be positive");
        }
        if (maxConcurrentSessions < 1) {
            throw new IllegalArgumentException("Maximum concurrent sessions must be positive");
        }
    }
}

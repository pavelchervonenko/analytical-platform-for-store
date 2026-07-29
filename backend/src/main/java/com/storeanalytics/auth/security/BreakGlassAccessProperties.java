package com.storeanalytics.auth.security;

import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.break-glass")
public record BreakGlassAccessProperties(Set<UUID> userIds) {

    public BreakGlassAccessProperties {
        userIds = userIds == null ? Set.of() : Set.copyOf(userIds);
    }

    public boolean contains(UUID userId) {
        return userIds.contains(userId);
    }
}

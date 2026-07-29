package com.storeanalytics.common.config;

import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.client-ip")
public record ClientIpProperties(List<String> trustedProxyCidrs) {

    private static final int MAXIMUM_TRUSTED_RANGES = 32;

    public ClientIpProperties {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (trustedProxyCidrs != null) {
            for (String configuredRange : trustedProxyCidrs) {
                String range = configuredRange == null
                        ? ""
                        : configuredRange.trim();
                if (!range.isEmpty()) {
                    normalized.add(range);
                }
            }
        }
        if (normalized.size() > MAXIMUM_TRUSTED_RANGES) {
            throw new IllegalArgumentException(
                    "At most 32 trusted proxy ranges may be configured"
            );
        }
        trustedProxyCidrs = List.copyOf(normalized);
    }
}

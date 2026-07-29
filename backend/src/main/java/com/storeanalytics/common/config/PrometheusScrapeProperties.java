package com.storeanalytics.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.observability.prometheus")
public record PrometheusScrapeProperties(String token) {

    private static final int MINIMUM_TOKEN_LENGTH = 32;
    private static final int MAXIMUM_TOKEN_LENGTH = 256;

    public PrometheusScrapeProperties {
        token = token == null ? "" : token.trim();
        if (!token.isEmpty() && (token.length() < MINIMUM_TOKEN_LENGTH
                || token.length() > MAXIMUM_TOKEN_LENGTH
                || token.chars().anyMatch(PrometheusScrapeProperties::isUnsafe))) {
            throw new IllegalArgumentException(
                    "Prometheus scrape token must contain 32-256 safe characters"
            );
        }
    }

    public boolean configured() {
        return !token.isEmpty();
    }

    private static boolean isUnsafe(int character) {
        return Character.isWhitespace(character)
                || Character.isISOControl(character);
    }
}

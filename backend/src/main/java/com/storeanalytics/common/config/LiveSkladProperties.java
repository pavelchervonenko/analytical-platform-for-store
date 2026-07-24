package com.storeanalytics.common.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.livesklad")
public record LiveSkladProperties(
        String baseUrl,
        String login,
        String password,
        List<String> allowedHosts,
        boolean requireHttps,
        Duration connectTimeout,
        Duration readTimeout
) {

    @ConstructorBinding
    public LiveSkladProperties {
        allowedHosts = allowedHosts == null
                ? List.of()
                : allowedHosts.stream()
                        .filter(StringUtils::hasText)
                        .map(value -> value.trim().toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList();
        validateBaseUrl(baseUrl, allowedHosts, requireHttps);
    }

    public LiveSkladProperties(
            String baseUrl,
            String login,
            String password,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        this(baseUrl, login, password, List.of(), false, connectTimeout, readTimeout);
    }

    private static void validateBaseUrl(
            String configuredBaseUrl,
            List<String> configuredAllowedHosts,
            boolean httpsRequired
    ) {
        if (!StringUtils.hasText(configuredBaseUrl)) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(configuredBaseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("LiveSklad base URL is invalid", exception);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("LiveSklad base URL must be an absolute server URL");
        }
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException("LiveSklad base URL must use HTTP or HTTPS");
        }
        if (httpsRequired && !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException("LiveSklad base URL must use HTTPS");
        }
        if (httpsRequired && configuredAllowedHosts.isEmpty()) {
            throw new IllegalArgumentException("LiveSklad allowed hosts must be configured");
        }
        if (!configuredAllowedHosts.isEmpty()
                && !configuredAllowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("LiveSklad base URL host is not allowed");
        }
    }
}

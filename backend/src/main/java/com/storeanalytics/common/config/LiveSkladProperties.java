package com.storeanalytics.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.livesklad")
public record LiveSkladProperties(
        String baseUrl,
        String login,
        String password,
        Duration connectTimeout,
        Duration readTimeout
) {
}

package com.storeanalytics.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record ApplicationSecurityProperties(List<String> corsAllowedOrigins) {
}

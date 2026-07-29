package com.storeanalytics.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.runtime")
public record ApplicationRuntimeProperties(
        @DefaultValue("COMBINED") ApplicationRole role
) {
}

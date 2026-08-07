package com.storeanalytics.interpretation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.interpretation")
public record InterpretationFeatureProperties(
        @DefaultValue("false") boolean snapshotEnabled,
        @DefaultValue("false") boolean generationEnabled,
        @DefaultValue("false") boolean publicationEnabled
) {
}

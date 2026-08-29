package com.storeanalytics.interpretation.review;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.interpretation.weekly-review")
public record WeeklyReviewProperties(
        @DefaultValue("false") boolean enabled
) {
}

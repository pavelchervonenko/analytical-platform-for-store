package com.storeanalytics.interpretation.review;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Additive read/write cutover; legacy v2 reads remain available for rollback. */
@ConfigurationProperties("app.interpretation.seller-weekly-review")
public record SellerWeeklyReviewProperties(@DefaultValue("false") boolean enabled) {
}

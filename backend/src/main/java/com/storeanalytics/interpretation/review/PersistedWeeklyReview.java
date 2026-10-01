package com.storeanalytics.interpretation.review;

import java.time.Instant;
import java.util.UUID;

/** Typed version-neutral header; concrete response codecs and scope checks remain version-specific. */
public sealed interface PersistedWeeklyReview permits PersistedWeeklyReviewSnapshot, PersistedWeeklyReviewV3Snapshot {
    UUID id();
    UUID storeId();
    int revision();
    WeeklyReviewContract response();
    String contentHash();
    Instant createdAt();
}

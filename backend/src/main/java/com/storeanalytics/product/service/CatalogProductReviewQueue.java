package com.storeanalytics.product.service;

import java.time.Instant;
import java.util.List;

public record CatalogProductReviewQueue(
        Instant activationFrom,
        List<CatalogProductReviewQueueItem> items,
        boolean hasMore
) { }

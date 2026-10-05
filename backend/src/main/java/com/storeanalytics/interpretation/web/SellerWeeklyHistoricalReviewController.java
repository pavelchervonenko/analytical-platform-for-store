package com.storeanalytics.interpretation.web;

import com.storeanalytics.interpretation.review.SellerWeeklyHistoricalReviewService;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewProperties;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewView;
import com.storeanalytics.interpretation.review.WeeklyReviewProperties;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Additive read endpoint; existing latest-week endpoints retain their current-roster contract. */
@RestController
public class SellerWeeklyHistoricalReviewController {
    private final SellerWeeklyHistoricalReviewService service;
    private final SellerWeeklyReviewProperties sellers;
    private final WeeklyReviewProperties reviews;

    public SellerWeeklyHistoricalReviewController(SellerWeeklyHistoricalReviewService service,
            SellerWeeklyReviewProperties sellers, WeeklyReviewProperties reviews) {
        this.service = service;
        this.sellers = sellers;
        this.reviews = reviews;
    }

    @GetMapping("/api/stores/{storeId}/weekly-reviews/seller-period")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    ResponseEntity<SellerWeeklyReviewView> period(@PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart) {
        if (!reviews.enabled() || !sellers.enabled()) {
            return ResponseEntity.notFound().header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(service.period(storeId, periodStart));
    }
}

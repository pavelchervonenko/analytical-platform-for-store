package com.storeanalytics.interpretation.web;

import com.storeanalytics.interpretation.review.SellerWeeklyReviewProperties;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewService;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewView;
import com.storeanalytics.interpretation.review.WeeklyReviewProperties;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Separate endpoints keep old clients and old STORE snapshots on the immutable v2 path. */
@RestController
public class SellerWeeklyReviewController {
    private final SellerWeeklyReviewService service;
    private final SellerWeeklyReviewProperties sellers;
    private final WeeklyReviewProperties reviews;

    public SellerWeeklyReviewController(SellerWeeklyReviewService service,
            SellerWeeklyReviewProperties sellers, WeeklyReviewProperties reviews) {
        this.service = service;
        this.sellers = sellers;
        this.reviews = reviews;
    }

    @GetMapping("/api/stores/{storeId}/weekly-reviews/seller-current")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    ResponseEntity<SellerWeeklyReviewView> current(@PathVariable UUID storeId) {
        return enabled() ? response(service.current(storeId)) : notFound();
    }

    @PostMapping("/api/admin/seller-weekly-reviews/stores/{storeId}/generate")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<SellerWeeklyReviewView> generate(@PathVariable UUID storeId) {
        return enabled() ? response(service.generate(storeId)) : notFound();
    }

    private boolean enabled() {
        return reviews.enabled() && sellers.enabled();
    }

    private ResponseEntity<SellerWeeklyReviewView> response(SellerWeeklyReviewView view) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(view);
    }

    private ResponseEntity<SellerWeeklyReviewView> notFound() {
        return ResponseEntity.notFound().header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
    }
}

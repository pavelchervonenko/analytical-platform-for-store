package com.storeanalytics.interpretation.web;

import com.storeanalytics.interpretation.review.WeeklyReviewProperties;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewService;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores/{storeId}/weekly-reviews")
public class WeeklyReviewController {

    private final WeeklyReviewService service;
    private final WeeklyReviewProperties properties;

    public WeeklyReviewController(
            WeeklyReviewService service,
            WeeklyReviewProperties properties
    ) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/current")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    ResponseEntity<WeeklyReviewResponse> current(@PathVariable UUID storeId) {
        if (!properties.enabled()) {
            return notFound();
        }
        return service.current(storeId)
                .map(response -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                        .body(response))
                .orElseGet(this::notFound);
    }

    private ResponseEntity<WeeklyReviewResponse> notFound() {
        return ResponseEntity.notFound()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .build();
    }
}

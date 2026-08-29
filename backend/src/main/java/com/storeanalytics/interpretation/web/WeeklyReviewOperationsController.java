package com.storeanalytics.interpretation.web;

import com.storeanalytics.interpretation.review.WeeklyReviewService;
import com.storeanalytics.interpretation.review.WeeklyReviewSnapshotView;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/weekly-reviews")
public class WeeklyReviewOperationsController {

    private final WeeklyReviewService service;

    public WeeklyReviewOperationsController(WeeklyReviewService service) {
        this.service = service;
    }

    @PostMapping("/stores/{storeId}/generate")
    @ResponseStatus(HttpStatus.CREATED)
    WeeklyReviewSnapshotView generate(@PathVariable UUID storeId) {
        return WeeklyReviewSnapshotView.from(service.generate(storeId));
    }
}

package com.storeanalytics.interpretation.web;

import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiJobView;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiOperatorService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/weekly-review-ai")
public class WeeklyReviewAiOperationsController {

    private final WeeklyReviewAiOperatorService service;

    public WeeklyReviewAiOperationsController(
            WeeklyReviewAiOperatorService service
    ) {
        this.service = service;
    }

    @GetMapping("/jobs/{jobId}")
    WeeklyReviewAiJobView findJob(@PathVariable UUID jobId) {
        return service.findJob(jobId);
    }

    @PostMapping("/snapshots/{snapshotId}/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    WeeklyReviewAiJobView generate(@PathVariable UUID snapshotId) {
        return service.generate(snapshotId);
    }
}

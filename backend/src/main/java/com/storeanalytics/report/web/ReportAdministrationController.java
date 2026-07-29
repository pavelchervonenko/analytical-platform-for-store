package com.storeanalytics.report.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.report.service.ReportBackfillJobCoordinator;
import com.storeanalytics.report.service.ReportBackfillJobService;
import com.storeanalytics.report.service.ReportBackfillJobView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reports")
public class ReportAdministrationController {

    private final ReportBackfillJobService jobService;
    private final ReportBackfillJobCoordinator coordinator;

    public ReportAdministrationController(
            ReportBackfillJobService jobService,
            ReportBackfillJobCoordinator coordinator
    ) {
        this.jobService = jobService;
        this.coordinator = coordinator;
    }

    @PostMapping("/backfill")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ReportBackfillJobView backfill(
            @RequestParam UUID storeId,
            @RequestParam int year,
            @RequestHeader(name = "Idempotency-Key", required = true)
            String idempotencyKey,
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        return jobService.create(
                storeId,
                year,
                principal.getUserId(),
                idempotencyKey
        );
    }

    @GetMapping("/backfill")
    List<ReportBackfillJobView> listBackfills(
            @RequestParam(defaultValue = "20") int limit
    ) {
        return jobService.list(limit);
    }

    @GetMapping("/backfill/{jobId}")
    ReportBackfillJobView getBackfill(@PathVariable UUID jobId) {
        return jobService.get(jobId);
    }

    @PostMapping("/backfill/{jobId}/cancel")
    ReportBackfillJobView cancelBackfill(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        return coordinator.cancel(jobId, principal.getUserId());
    }
}

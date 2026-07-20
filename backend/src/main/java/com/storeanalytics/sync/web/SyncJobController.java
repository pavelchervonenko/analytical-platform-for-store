package com.storeanalytics.sync.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.sync.service.SyncJobCoordinator;
import com.storeanalytics.sync.service.SyncJobService;
import com.storeanalytics.sync.service.SyncJobView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync/jobs")
public class SyncJobController {

    private final SyncJobService jobService;
    private final SyncJobCoordinator coordinator;

    public SyncJobController(
            SyncJobService jobService,
            SyncJobCoordinator coordinator
    ) {
        this.jobService = jobService;
        this.coordinator = coordinator;
    }

    @PostMapping("/backfill")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SyncJobView createBackfill(
            @Valid @RequestBody CreateBackfillSyncJobRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        return jobService.createBackfill(
                request.periodStart(),
                request.periodEndInclusive(),
                principal.getUserId()
        );
    }

    @GetMapping
    List<SyncJobView> list(@RequestParam(defaultValue = "20") int limit) {
        return jobService.list(limit);
    }

    @GetMapping("/{jobId}")
    SyncJobView get(@PathVariable UUID jobId) {
        return jobService.get(jobId);
    }

    @PostMapping("/{jobId}/cancel")
    SyncJobView cancel(@PathVariable UUID jobId) {
        return coordinator.cancel(jobId);
    }
}

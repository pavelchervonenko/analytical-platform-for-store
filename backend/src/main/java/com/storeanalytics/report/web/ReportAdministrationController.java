package com.storeanalytics.report.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.report.service.ReportBackfillResult;
import com.storeanalytics.report.service.ReportBackfillService;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reports")
public class ReportAdministrationController {

    private final ReportBackfillService backfillService;

    public ReportAdministrationController(ReportBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @PostMapping("/backfill")
    ReportBackfillResult backfill(
            @RequestParam UUID storeId,
            @RequestParam int year,
            Authentication authentication
    ) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return backfillService.backfill(storeId, year, principal.getUserId());
    }
}

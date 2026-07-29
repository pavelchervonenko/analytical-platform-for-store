package com.storeanalytics.report.web;

import com.storeanalytics.common.web.PageResponse;
import com.storeanalytics.metrics.model.ReportType;
import com.storeanalytics.report.service.ReportDetailView;
import com.storeanalytics.report.service.ReportQueryService;
import com.storeanalytics.report.service.ReportSummaryView;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores/{storeId}/reports")
public class ReportController {

    private final ReportQueryService reportQueryService;

    public ReportController(ReportQueryService reportQueryService) {
        this.reportQueryService = reportQueryService;
    }

    @GetMapping
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    PageResponse<ReportSummaryView> list(
            @PathVariable UUID storeId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) ReportType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return reportQueryService.list(storeId, year, type, page, size);
    }

    @GetMapping("/years")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    List<Integer> years(@PathVariable UUID storeId) {
        return reportQueryService.years(storeId);
    }

    @GetMapping("/{reportId}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    ReportDetailView get(
            @PathVariable UUID storeId,
            @PathVariable UUID reportId
    ) {
        return reportQueryService.get(storeId, reportId);
    }
}

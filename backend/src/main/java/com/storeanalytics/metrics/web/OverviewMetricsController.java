package com.storeanalytics.metrics.web;

import com.storeanalytics.metrics.service.OverviewMetricScope;
import com.storeanalytics.metrics.service.OverviewMetricsResult;
import com.storeanalytics.metrics.service.OverviewMetricsService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores")
public class OverviewMetricsController {

    private final OverviewMetricsService overviewMetricsService;

    public OverviewMetricsController(OverviewMetricsService overviewMetricsService) {
        this.overviewMetricsService = overviewMetricsService;
    }

    @GetMapping("/{storeId}/overview-metrics")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    OverviewMetricsResult get(
            @PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd,
            @RequestParam(defaultValue = "SELLERS") OverviewMetricScope scope
    ) {
        return overviewMetricsService.calculate(
                storeId,
                new StoreKpiPeriod(periodStart, periodEnd),
                scope
        );
    }
}

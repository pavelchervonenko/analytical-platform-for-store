package com.storeanalytics.metrics.web;

import com.storeanalytics.metrics.service.AverageKpiResult;
import com.storeanalytics.metrics.service.AverageKpiService;
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
public class AverageKpiController {

    private final AverageKpiService averageKpiService;

    public AverageKpiController(AverageKpiService averageKpiService) {
        this.averageKpiService = averageKpiService;
    }

    @GetMapping("/{storeId}/kpi/averages")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    AverageKpiResult getAverageKpi(
            @PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd
    ) {
        return averageKpiService.calculate(
                storeId,
                new StoreKpiPeriod(periodStart, periodEnd)
        );
    }
}

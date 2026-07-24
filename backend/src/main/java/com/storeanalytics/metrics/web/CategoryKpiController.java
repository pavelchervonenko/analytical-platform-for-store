package com.storeanalytics.metrics.web;

import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.CategoryKpiService;
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
public class CategoryKpiController {

    private final CategoryKpiService categoryKpiService;

    public CategoryKpiController(CategoryKpiService categoryKpiService) {
        this.categoryKpiService = categoryKpiService;
    }

    @GetMapping("/{storeId}/kpi/categories")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    CategoryKpiResult getCategoryKpi(
            @PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd
    ) {
        return categoryKpiService.calculate(
                storeId,
                new StoreKpiPeriod(periodStart, periodEnd)
        );
    }
}

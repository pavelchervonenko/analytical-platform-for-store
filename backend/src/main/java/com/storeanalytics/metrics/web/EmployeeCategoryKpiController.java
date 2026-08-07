package com.storeanalytics.metrics.web;

import com.storeanalytics.metrics.service.EmployeeCategoryKpiResult;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiService;
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
public class EmployeeCategoryKpiController {

    private final EmployeeCategoryKpiService service;

    public EmployeeCategoryKpiController(EmployeeCategoryKpiService service) {
        this.service = service;
    }

    @GetMapping("/{storeId}/kpi/employees/categories")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    EmployeeCategoryKpiResult getEmployeeCategoryKpi(
            @PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd
    ) {
        return service.calculate(
                storeId,
                new StoreKpiPeriod(periodStart, periodEnd)
        );
    }
}

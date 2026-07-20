package com.storeanalytics.metrics.web;

import com.storeanalytics.metrics.service.EmployeeKpiResult;
import com.storeanalytics.metrics.service.EmployeeKpiService;
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
public class EmployeeKpiController {

    private final EmployeeKpiService employeeKpiService;

    public EmployeeKpiController(EmployeeKpiService employeeKpiService) {
        this.employeeKpiService = employeeKpiService;
    }

    @GetMapping("/{storeId}/kpi/employees")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    EmployeeKpiResult getEmployeeKpi(
            @PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd
    ) {
        return employeeKpiService.calculate(
                storeId,
                new StoreKpiPeriod(periodStart, periodEnd)
        );
    }
}

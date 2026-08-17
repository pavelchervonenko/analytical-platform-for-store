package com.storeanalytics.performance.web;

import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.service.EmployeeCardService;
import com.storeanalytics.performance.service.EmployeeCardView;
import com.storeanalytics.performance.service.EmployeeComparisonMode;
import com.storeanalytics.performance.service.EmployeeDirectoryView;
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
public class EmployeeCardController {

    private final EmployeeCardService cardService;

    public EmployeeCardController(EmployeeCardService cardService) {
        this.cardService = cardService;
    }

    @GetMapping("/{storeId}/employees")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    EmployeeDirectoryView directory(
            @PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd
    ) {
        return cardService.directory(
                storeId, new StoreKpiPeriod(periodStart, periodEnd)
        );
    }

    @GetMapping("/{storeId}/employees/{employeeId}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    EmployeeCardView card(
            @PathVariable UUID storeId,
            @PathVariable UUID employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd,
            @RequestParam(defaultValue = "PREVIOUS_PERIOD")
            EmployeeComparisonMode comparisonMode
    ) {
        return cardService.card(
                storeId,
                employeeId,
                new StoreKpiPeriod(periodStart, periodEnd),
                comparisonMode
        );
    }
}

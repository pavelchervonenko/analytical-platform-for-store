package com.storeanalytics.salary.web;

import com.storeanalytics.salary.service.PayrollRevisionComparisonService;
import com.storeanalytics.salary.service.PayrollRevisionComparisonView;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores")
public class PayrollRevisionComparisonController {

    private final PayrollRevisionComparisonService comparisonService;

    public PayrollRevisionComparisonController(
            PayrollRevisionComparisonService comparisonService
    ) {
        this.comparisonService = comparisonService;
    }

    @GetMapping("/{storeId}/payroll-runs/{previousRunId}/compare/{currentRunId}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    PayrollRevisionComparisonView compare(
            @PathVariable UUID storeId,
            @PathVariable UUID previousRunId,
            @PathVariable UUID currentRunId
    ) {
        return comparisonService.compare(storeId, previousRunId, currentRunId);
    }
}

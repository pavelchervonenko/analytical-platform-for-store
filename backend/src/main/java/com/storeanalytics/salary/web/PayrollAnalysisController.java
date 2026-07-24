package com.storeanalytics.salary.web;

import com.storeanalytics.salary.service.PayrollPreviewService;
import com.storeanalytics.salary.service.PayrollPreviewView;
import com.storeanalytics.salary.service.PayrollReadinessService;
import com.storeanalytics.salary.service.PayrollReadinessView;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores")
public class PayrollAnalysisController {

    private final PayrollReadinessService readinessService;
    private final PayrollPreviewService previewService;

    public PayrollAnalysisController(
            PayrollReadinessService readinessService,
            PayrollPreviewService previewService
    ) {
        this.readinessService = readinessService;
        this.previewService = previewService;
    }

    @GetMapping("/{storeId}/payroll/{month}/readiness")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    PayrollReadinessView readiness(
            @PathVariable UUID storeId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month
    ) {
        return readinessService.inspect(storeId, month);
    }

    @GetMapping("/{storeId}/payroll/{month}/preview")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    PayrollPreviewView preview(
            @PathVariable UUID storeId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month
    ) {
        return previewService.preview(storeId, month);
    }
}

package com.storeanalytics.performance.web;

import com.storeanalytics.performance.service.StorePlanProgressService;
import com.storeanalytics.performance.service.StorePlanProgressView;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StorePlanProgressController {

    private final StorePlanProgressService progressService;

    public StorePlanProgressController(StorePlanProgressService progressService) {
        this.progressService = progressService;
    }

    @GetMapping("/api/stores/{storeId}/performance-plans/{month}/progress")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    StorePlanProgressView get(
            @PathVariable UUID storeId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
    ) {
        return progressService.calculate(storeId, month, asOf);
    }
}

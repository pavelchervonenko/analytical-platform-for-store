package com.storeanalytics.metrics.web;

import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.AttachRateService;
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
public class AttachRateController {

    private final AttachRateService attachRateService;

    public AttachRateController(AttachRateService attachRateService) {
        this.attachRateService = attachRateService;
    }

    @GetMapping("/{storeId}/kpi/attach-rates")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    AttachRateResult getAttachRates(
            @PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd
    ) {
        return attachRateService.calculate(
                storeId,
                new StoreKpiPeriod(periodStart, periodEnd)
        );
    }
}

package com.storeanalytics.quality.web;

import com.storeanalytics.quality.service.StorePeriodQualityService;
import com.storeanalytics.quality.service.StorePeriodQualityView;
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
public class StorePeriodQualityController {

    private final StorePeriodQualityService qualityService;

    public StorePeriodQualityController(StorePeriodQualityService qualityService) {
        this.qualityService = qualityService;
    }

    @GetMapping("/api/stores/{storeId}/period-quality/{month}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    StorePeriodQualityView get(
            @PathVariable UUID storeId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate asOf
    ) {
        return qualityService.inspect(storeId, month, asOf);
    }
}

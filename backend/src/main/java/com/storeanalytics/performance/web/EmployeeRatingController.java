package com.storeanalytics.performance.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.service.EmployeeRatingFinalizationService;
import com.storeanalytics.performance.service.EmployeeRatingQueryService;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores")
public class EmployeeRatingController {

    private final EmployeeRatingQueryService ratingService;
    private final EmployeeRatingFinalizationService finalizationService;

    public EmployeeRatingController(
            EmployeeRatingQueryService ratingService,
            EmployeeRatingFinalizationService finalizationService
    ) {
        this.ratingService = ratingService;
        this.finalizationService = finalizationService;
    }

    @GetMapping("/{storeId}/employee-ratings")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    EmployeeRatingResult get(
            @PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd
    ) {
        return ratingService.get(storeId, new StoreKpiPeriod(periodStart, periodEnd));
    }

    @PostMapping("/{storeId}/employee-ratings/finalize")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    EmployeeRatingResult finalizeRating(
            @PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd,
            Authentication authentication
    ) {
        return finalizationService.finalizePeriod(
                storeId,
                new StoreKpiPeriod(periodStart, periodEnd),
                ((AppUserPrincipal) authentication.getPrincipal()).getUserId()
        );
    }
}

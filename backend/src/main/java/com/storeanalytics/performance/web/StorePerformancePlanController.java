package com.storeanalytics.performance.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.performance.model.StorePlanTargets;
import com.storeanalytics.performance.service.StorePerformancePlanService;
import com.storeanalytics.performance.service.StorePerformancePlanView;
import jakarta.validation.Valid;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores")
public class StorePerformancePlanController {

    private final StorePerformancePlanService planService;

    public StorePerformancePlanController(StorePerformancePlanService planService) {
        this.planService = planService;
    }

    @GetMapping("/{storeId}/performance-plans/{month}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    StorePerformancePlanView get(
            @PathVariable UUID storeId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month
    ) {
        return planService.get(storeId, month);
    }

    @PutMapping("/{storeId}/performance-plans/{month}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    StorePerformancePlanView upsert(
            @PathVariable UUID storeId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @Valid @RequestBody StorePerformancePlanRequest request,
            Authentication authentication
    ) {
        return planService.upsert(
                storeId,
                month,
                new StorePlanTargets(
                        request.revenueTarget(),
                        request.accessoryShareTarget(),
                        request.serviceShareTarget(),
                        request.additionalShareTarget()
                ),
                principal(authentication).getUserId()
        );
    }

    private AppUserPrincipal principal(Authentication authentication) {
        return (AppUserPrincipal) authentication.getPrincipal();
    }
}

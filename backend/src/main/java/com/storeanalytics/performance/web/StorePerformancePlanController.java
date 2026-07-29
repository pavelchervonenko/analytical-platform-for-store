package com.storeanalytics.performance.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.performance.model.StorePlanTargets;
import com.storeanalytics.performance.service.StorePerformancePlanService;
import com.storeanalytics.performance.service.StorePerformancePlanView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores")
public class StorePerformancePlanController {

    private final StorePerformancePlanService planService;

    public StorePerformancePlanController(StorePerformancePlanService planService) {
        this.planService = planService;
    }

    @Operation(responses = @ApiResponse(
            responseCode = "200",
            useReturnTypeSchema = true,
            headers = @Header(
                    name = HttpHeaders.ETAG,
                    description = "Strong entity tag for the returned plan version",
                    schema = @Schema(type = "string")
            )
    ))
    @GetMapping("/{storeId}/performance-plans/{month}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    ResponseEntity<StorePerformancePlanView> get(
            @PathVariable UUID storeId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month
    ) {
        StorePerformancePlanView plan = planService.get(storeId, month);
        return ResponseEntity.ok()
                .eTag(StorePerformancePlanService.etag(plan))
                .body(plan);
    }

    @Operation(responses = @ApiResponse(
            responseCode = "200",
            useReturnTypeSchema = true,
            headers = @Header(
                    name = HttpHeaders.ETAG,
                    description = "Strong entity tag for the returned plan version",
                    schema = @Schema(type = "string")
            )
    ))
    @PutMapping("/{storeId}/performance-plans/{month}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    ResponseEntity<StorePerformancePlanView> upsert(
            @PathVariable UUID storeId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @Valid @RequestBody StorePerformancePlanRequest request,
            @Parameter(description = "Current strong ETag when updating an existing plan")
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @Parameter(description = "Must be * when creating an absent plan")
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
            String ifNoneMatch,
            Authentication authentication
    ) {
        StorePerformancePlanView plan = planService.upsert(
                storeId,
                month,
                new StorePlanTargets(
                        request.revenueTarget(),
                        request.accessoryShareTarget(),
                        request.serviceShareTarget(),
                        request.additionalShareTarget()
                ),
                ifMatch,
                ifNoneMatch,
                principal(authentication).getUserId()
        );
        return ResponseEntity.ok()
                .eTag(StorePerformancePlanService.etag(plan))
                .body(plan);
    }

    private AppUserPrincipal principal(Authentication authentication) {
        return (AppUserPrincipal) authentication.getPrincipal();
    }
}

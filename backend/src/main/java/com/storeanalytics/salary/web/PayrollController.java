package com.storeanalytics.salary.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.common.web.PageResponse;
import com.storeanalytics.salary.service.AddPayrollAdjustmentCommand;
import com.storeanalytics.salary.service.PayrollManagementService;
import com.storeanalytics.salary.service.PayrollRunDetailView;
import com.storeanalytics.salary.service.PayrollRunListItemView;
import com.storeanalytics.salary.service.VoidPayrollAdjustmentCommand;
import jakarta.validation.Valid;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/stores")
public class PayrollController {

    private final PayrollManagementService payrollService;

    public PayrollController(PayrollManagementService payrollService) {
        this.payrollService = payrollService;
    }

    @PostMapping("/{storeId}/payroll/{month}/calculate")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    PayrollRunDetailView calculate(
            @PathVariable UUID storeId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestBody(required = false) PayrollCalculateRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication
    ) {
        return payrollService.calculate(
                storeId,
                month,
                request == null ? null : request.revisionReason(),
                principal(authentication).getUserId(),
                idempotencyKey
        );
    }

    @GetMapping("/{storeId}/payroll/{month}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    PayrollRunDetailView latest(
            @PathVariable UUID storeId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM") YearMonth month
    ) {
        return payrollService.latest(storeId, month);
    }

    @GetMapping("/{storeId}/payroll-runs")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    PageResponse<PayrollRunListItemView> list(
            @PathVariable UUID storeId,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return payrollService.list(storeId, month, page, size);
    }

    @GetMapping("/{storeId}/payroll-runs/{runId}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    PayrollRunDetailView get(
            @PathVariable UUID storeId,
            @PathVariable UUID runId
    ) {
        return payrollService.get(storeId, runId);
    }

    @PostMapping("/{storeId}/payroll-runs/{runId}/adjustments")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    PayrollRunDetailView addAdjustment(
            @PathVariable UUID storeId,
            @PathVariable UUID runId,
            @Valid @RequestBody PayrollAdjustmentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication
    ) {
        return payrollService.addAdjustment(new AddPayrollAdjustmentCommand(
                storeId,
                runId,
                request.employeeId(),
                request.type(),
                request.amount(),
                request.reason(),
                request.runVersion(),
                principal(authentication).getUserId()
        ), idempotencyKey);
    }

    @PostMapping("/{storeId}/payroll-runs/{runId}/adjustments/{adjustmentId}/void")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    PayrollRunDetailView voidAdjustment(
            @PathVariable UUID storeId,
            @PathVariable UUID runId,
            @PathVariable UUID adjustmentId,
            @Valid @RequestBody PayrollVoidAdjustmentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication
    ) {
        return payrollService.voidAdjustment(
                new VoidPayrollAdjustmentCommand(
                        storeId,
                        runId,
                        adjustmentId,
                        request.reason(),
                        request.runVersion(),
                        request.adjustmentVersion(),
                        principal(authentication).getUserId()
                ),
                idempotencyKey
        );
    }

    @PostMapping("/{storeId}/payroll-runs/{runId}/approve")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    PayrollRunDetailView approve(
            @PathVariable UUID storeId,
            @PathVariable UUID runId,
            @Valid @RequestBody PayrollTransitionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication
    ) {
        return payrollService.approve(
                storeId,
                runId,
                request.version(),
                principal(authentication).getUserId(),
                idempotencyKey
        );
    }

    @PostMapping("/{storeId}/payroll-runs/{runId}/paid")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    PayrollRunDetailView markPaid(
            @PathVariable UUID storeId,
            @PathVariable UUID runId,
            @Valid @RequestBody PayrollTransitionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication
    ) {
        return payrollService.markPaid(
                storeId,
                runId,
                request.version(),
                principal(authentication).getUserId(),
                idempotencyKey
        );
    }

    private AppUserPrincipal principal(Authentication authentication) {
        return (AppUserPrincipal) authentication.getPrincipal();
    }
}

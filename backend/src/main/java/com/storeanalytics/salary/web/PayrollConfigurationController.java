package com.storeanalytics.salary.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.salary.model.PayrollSchemeDefinition;
import com.storeanalytics.salary.service.PayrollConfigurationService;
import com.storeanalytics.salary.service.PayrollSchemeView;
import com.storeanalytics.salary.service.ProductPayrollCategoryView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class PayrollConfigurationController {

    private final PayrollConfigurationService configurationService;

    public PayrollConfigurationController(PayrollConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    @GetMapping("/payroll-schemes")
    List<PayrollSchemeView> schemes() {
        return configurationService.schemes();
    }

    @PostMapping("/payroll-schemes")
    PayrollSchemeView addScheme(
            @Valid @RequestBody PayrollSchemeRequest request,
            Authentication authentication
    ) {
        return configurationService.addScheme(
                request.code(),
                request.effectiveFrom(),
                new PayrollSchemeDefinition(
                        request.achievedPercentage(),
                        request.missedPercentage(),
                        request.achievedTier1Rate(),
                        request.missedTier1Rate(),
                        request.achievedTier2Rate(),
                        request.missedTier2Rate(),
                        request.advanceAmount()
                ),
                principal(authentication).getUserId()
        );
    }

    @GetMapping("/products/{productId}/payroll-category-assignments")
    List<ProductPayrollCategoryView> productAssignments(@PathVariable UUID productId) {
        return configurationService.productAssignments(productId);
    }

    @PostMapping("/products/{productId}/payroll-category-assignments")
    ProductPayrollCategoryView assignProduct(
            @PathVariable UUID productId,
            @Valid @RequestBody ProductPayrollCategoryRequest request,
            Authentication authentication
    ) {
        return configurationService.assignProduct(
                productId,
                request.categoryCode(),
                request.validFrom(),
                request.reason(),
                principal(authentication).getUserId()
        );
    }

    private AppUserPrincipal principal(Authentication authentication) {
        return (AppUserPrincipal) authentication.getPrincipal();
    }
}

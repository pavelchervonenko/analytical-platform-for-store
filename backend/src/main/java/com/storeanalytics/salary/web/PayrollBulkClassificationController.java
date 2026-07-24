package com.storeanalytics.salary.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.salary.service.PayrollBulkClassificationService;
import com.storeanalytics.salary.service.PayrollProductCategoryChange;
import com.storeanalytics.salary.service.ProductPayrollCategoryView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/payroll-category-assignments")
public class PayrollBulkClassificationController {

    private final PayrollBulkClassificationService classificationService;

    public PayrollBulkClassificationController(
            PayrollBulkClassificationService classificationService
    ) {
        this.classificationService = classificationService;
    }

    @PostMapping("/bulk")
    List<ProductPayrollCategoryView> assign(
            @Valid @RequestBody PayrollBulkCategoryRequest request,
            Authentication authentication
    ) {
        return classificationService.assign(
                request.validFrom(),
                request.reason(),
                request.assignments().stream()
                        .map(item -> new PayrollProductCategoryChange(
                                item.productId(), item.categoryCode()
                        ))
                        .toList(),
                principal(authentication).getUserId()
        );
    }

    private AppUserPrincipal principal(Authentication authentication) {
        return (AppUserPrincipal) authentication.getPrincipal();
    }
}

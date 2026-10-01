package com.storeanalytics.product.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.product.service.CatalogProductReviewDecision;
import com.storeanalytics.product.service.CatalogProductReviewDecisionResult;
import com.storeanalytics.product.service.CatalogProductReviewDecisionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/catalog-product-reviews")
public class CatalogProductReviewDecisionController {
    private final CatalogProductReviewDecisionService decisions;

    public CatalogProductReviewDecisionController(CatalogProductReviewDecisionService decisions) {
        this.decisions = decisions;
    }

    @PostMapping("/{productId}/decision")
    CatalogProductReviewDecisionResult decide(
            @PathVariable("productId") UUID productId, @Valid @RequestBody CatalogProductReviewDecisionRequest request,
            Authentication authentication
    ) {
        return decisions.decide(productId, new CatalogProductReviewDecision(
                request.expectedProductVersion(), request.analyticsCategoryCode(),
                request.conditionType(), request.payrollCategoryCode(), request.reason()
        ), ((AppUserPrincipal) authentication.getPrincipal()).getUserId());
    }

}

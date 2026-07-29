package com.storeanalytics.performance.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.common.web.PageResponse;
import com.storeanalytics.performance.model.RatingSchemeDefinition;
import com.storeanalytics.performance.service.RatingSchemeService;
import com.storeanalytics.performance.service.RatingSchemeView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/rating-schemes")
public class RatingSchemeController {

    private final RatingSchemeService schemeService;

    public RatingSchemeController(RatingSchemeService schemeService) {
        this.schemeService = schemeService;
    }

    @GetMapping
    PageResponse<RatingSchemeView> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return schemeService.findAll(page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RatingSchemeView create(
            @Valid @RequestBody RatingSchemeRequest request,
            Authentication authentication
    ) {
        return schemeService.create(
                request.code(),
                request.effectiveFrom(),
                new RatingSchemeDefinition(
                        request.contributionWeight(),
                        request.efficiencyWeight(),
                        request.structureWeight(),
                        request.attachWeight(),
                        request.accessoryStructureWeight(),
                        request.serviceStructureWeight(),
                        request.minimumAttachDenominator(),
                        request.scoreCap(),
                        request.minimumCoveragePercent()
                ),
                ((AppUserPrincipal) authentication.getPrincipal()).getUserId()
        );
    }
}

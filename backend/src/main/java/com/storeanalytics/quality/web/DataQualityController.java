package com.storeanalytics.quality.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.quality.service.DataQualityOverviewView;
import com.storeanalytics.quality.service.DataQualityService;
import com.storeanalytics.quality.service.StoreDataQualityView;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DataQualityController {

    private final DataQualityService dataQualityService;

    public DataQualityController(DataQualityService dataQualityService) {
        this.dataQualityService = dataQualityService;
    }

    @GetMapping("/api/data-quality/summary")
    @PreAuthorize("isAuthenticated() and !hasAuthority('PASSWORD_CHANGE_REQUIRED')")
    DataQualityOverviewView overview(Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return dataQualityService.overview(principal.getUserId(), principal.getRole());
    }

    @GetMapping("/api/stores/{storeId}/data-quality")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    StoreDataQualityView get(@PathVariable UUID storeId) {
        return dataQualityService.get(storeId);
    }
}

package com.storeanalytics.store.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.store.service.StoreCatalogService;
import com.storeanalytics.store.service.StoreSummaryView;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores")
public class StoreDirectoryController {

    private final StoreCatalogService storeCatalogService;

    public StoreDirectoryController(StoreCatalogService storeCatalogService) {
        this.storeCatalogService = storeCatalogService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated() and !hasAuthority('PASSWORD_CHANGE_REQUIRED')")
    List<StoreSummaryView> findAccessible(Authentication authentication) {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        return storeCatalogService.findAccessible(principal.getUserId(), principal.getRole());
    }
}

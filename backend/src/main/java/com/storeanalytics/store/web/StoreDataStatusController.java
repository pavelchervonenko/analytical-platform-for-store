package com.storeanalytics.store.web;

import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores")
public class StoreDataStatusController {

    private final StoreDataStatusService statusService;

    public StoreDataStatusController(StoreDataStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/{storeId}/data-status")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    StoreDataStatusView get(@PathVariable UUID storeId) {
        return statusService.get(storeId);
    }
}

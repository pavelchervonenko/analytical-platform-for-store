package com.storeanalytics.sync.web;

import com.storeanalytics.sync.service.SalesSyncPeriod;
import com.storeanalytics.sync.service.SalesSyncResult;
import com.storeanalytics.sync.service.SalesSyncService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class SalesSyncController {

    private final SalesSyncService salesSyncService;

    public SalesSyncController(SalesSyncService salesSyncService) {
        this.salesSyncService = salesSyncService;
    }

    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.OK)
    SalesSyncResult synchronizeSales(@Valid @RequestBody SalesSyncRequest request) {
        return salesSyncService.synchronize(new SalesSyncPeriod(
                request.periodStart(),
                request.periodEnd()
        ));
    }
}

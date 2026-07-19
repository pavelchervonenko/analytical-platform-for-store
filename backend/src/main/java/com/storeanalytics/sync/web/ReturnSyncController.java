package com.storeanalytics.sync.web;

import com.storeanalytics.sync.service.ReturnSyncPeriod;
import com.storeanalytics.sync.service.ReturnSyncResult;
import com.storeanalytics.sync.service.ReturnSyncService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class ReturnSyncController {

    private final ReturnSyncService returnSyncService;

    public ReturnSyncController(ReturnSyncService returnSyncService) {
        this.returnSyncService = returnSyncService;
    }

    @PostMapping("/returns")
    @ResponseStatus(HttpStatus.OK)
    ReturnSyncResult synchronizeReturns(
            @Valid @RequestBody ReturnSyncRequest request
    ) {
        return returnSyncService.synchronize(new ReturnSyncPeriod(
                request.periodStart(),
                request.periodEnd()
        ));
    }
}

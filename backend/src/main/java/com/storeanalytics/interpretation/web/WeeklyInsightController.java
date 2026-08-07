package com.storeanalytics.interpretation.web;

import com.storeanalytics.interpretation.query.WeeklyInsightQueryService;
import com.storeanalytics.interpretation.query.WeeklyInsightResponse;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores/{storeId}/insights/weekly")
public class WeeklyInsightController {

    private final WeeklyInsightQueryService queryService;

    public WeeklyInsightController(WeeklyInsightQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/current")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    ResponseEntity<WeeklyInsightResponse> current(@PathVariable UUID storeId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(queryService.current(storeId));
    }
}

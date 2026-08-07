package com.storeanalytics.interpretation.web;

import com.storeanalytics.common.web.PageResponse;
import com.storeanalytics.interpretation.query.WeeklyInterpretationDetailView;
import com.storeanalytics.interpretation.query.WeeklyInterpretationQueryService;
import com.storeanalytics.interpretation.query.WeeklyInterpretationSummaryView;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores/{storeId}/interpretations/weekly")
public class WeeklyInterpretationController {

    private final WeeklyInterpretationQueryService queryService;

    public WeeklyInterpretationController(
            WeeklyInterpretationQueryService queryService
    ) {
        this.queryService = queryService;
    }

    @GetMapping
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    PageResponse<WeeklyInterpretationSummaryView> list(
            @PathVariable UUID storeId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStartFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEndTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size
    ) {
        return queryService.list(
                storeId, periodStartFrom, periodEndTo, page, size
        );
    }

    @GetMapping("/latest")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    WeeklyInterpretationDetailView latest(@PathVariable UUID storeId) {
        return queryService.latest(storeId);
    }

    @GetMapping("/{interpretationId}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    WeeklyInterpretationDetailView get(
            @PathVariable UUID storeId,
            @PathVariable UUID interpretationId
    ) {
        return queryService.get(storeId, interpretationId);
    }
}

package com.storeanalytics.metrics.cases;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores/{storeId}/attach-rate/cases")
@PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
public class CaseAttachController {
    private final CaseAttachService service;

    public CaseAttachController(CaseAttachService service) {
        this.service = service;
    }

    @GetMapping
    CaseAttachViews.Queue queue(@PathVariable UUID storeId,
                                @RequestParam(defaultValue = "OPEN") String state,
                                @RequestParam(defaultValue = "0") int offset,
                                @RequestParam(defaultValue = "30") int limit) {
        return service.queue(storeId, state, offset, limit);
    }

    @GetMapping("/estimates")
    CaseAttachViews.EstimateResult estimates(
            @PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd) {
        return service.estimates(storeId, new StoreKpiPeriod(periodStart, periodEnd));
    }

    @GetMapping("/{sourceId}")
    ResponseEntity<CaseAttachViews.Detail> detail(@PathVariable UUID storeId,
                                                   @PathVariable UUID sourceId) {
        return response(service.detail(storeId, sourceId));
    }

    @PostMapping("/{sourceId}/preview")
    CaseAttachViews.Preview preview(@PathVariable UUID storeId, @PathVariable UUID sourceId,
                                    @Valid @RequestBody CaseAttachDecisionRequest request) {
        return service.preview(storeId, sourceId, request);
    }

    @PostMapping("/{sourceId}/decisions")
    ResponseEntity<CaseAttachViews.Detail> decide(
            @PathVariable UUID storeId, @PathVariable UUID sourceId,
            @Valid @RequestBody CaseAttachDecisionRequest request,
            @RequestHeader(value = "If-Match", required = false) String expected,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication
    ) {
        return response(service.decide(storeId, sourceId, request, expected, key,
                ((AppUserPrincipal) authentication.getPrincipal()).getUserId()));
    }

    private ResponseEntity<CaseAttachViews.Detail> response(CaseAttachViews.Detail detail) {
        return ResponseEntity.ok().eTag(CaseAttachService.etag(detail.item())).body(detail);
    }
}

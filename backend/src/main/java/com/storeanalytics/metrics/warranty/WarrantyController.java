package com.storeanalytics.metrics.warranty;

import com.storeanalytics.auth.security.AppUserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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
@RequestMapping("/api/stores/{storeId}/attach-rate/warranties")
@PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
public class WarrantyController {
    private final WarrantyService service;

    public WarrantyController(WarrantyService service) {
        this.service = service;
    }

    @GetMapping
    WarrantyViews.Queue queue(@PathVariable UUID storeId,
                             @RequestParam(defaultValue = "OPEN") String state,
                             @RequestParam(defaultValue = "0") int offset,
                             @RequestParam(defaultValue = "30") int limit) {
        return service.queue(storeId, state, offset, limit);
    }

    @GetMapping("/devices")
    List<WarrantyViews.Device> devices(@PathVariable UUID storeId, @RequestParam String query) {
        return service.search(storeId, query);
    }

    @GetMapping("/originals")
    List<WarrantyViews.Case> originals(@PathVariable UUID storeId, @RequestParam String query) {
        return service.searchOriginals(storeId, query);
    }

    @GetMapping("/{sourceId}")
    ResponseEntity<WarrantyViews.Detail> detail(@PathVariable UUID storeId, @PathVariable UUID sourceId) {
        return response(service.detail(storeId, sourceId));
    }

    @PostMapping("/{sourceId}/preview")
    WarrantyViews.Preview preview(@PathVariable UUID storeId, @PathVariable UUID sourceId,
                                  @Valid @RequestBody WarrantyDecisionRequest request) {
        return service.preview(storeId, sourceId, request);
    }

    @PostMapping("/{sourceId}/decisions")
    ResponseEntity<WarrantyViews.Detail> decide(
            @PathVariable UUID storeId, @PathVariable UUID sourceId,
            @Valid @RequestBody WarrantyDecisionRequest request,
            @RequestHeader(value = "If-Match", required = false) String expected,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication
    ) {
        return response(service.decide(storeId, sourceId, request, expected, key,
                ((AppUserPrincipal) authentication.getPrincipal()).getUserId()));
    }

    private ResponseEntity<WarrantyViews.Detail> response(WarrantyViews.Detail detail) {
        return ResponseEntity.ok().eTag(WarrantyService.etag(detail.warranty())).body(detail);
    }
}

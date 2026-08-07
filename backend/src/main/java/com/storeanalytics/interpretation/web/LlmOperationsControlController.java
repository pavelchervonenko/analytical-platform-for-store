package com.storeanalytics.interpretation.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.interpretation.operations.LlmOperationsService;
import com.storeanalytics.interpretation.operations.ManualLlmActionRequest;
import com.storeanalytics.interpretation.operations.ManualLlmJobView;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/llm")
public class LlmOperationsControlController {

    private final LlmOperationsService service;

    public LlmOperationsControlController(LlmOperationsService service) {
        this.service = service;
    }

    @PostMapping("/snapshots/{snapshotId}/regenerate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ManualLlmJobView regenerate(
            @PathVariable UUID snapshotId,
            @RequestHeader(name = "Idempotency-Key", required = true)
            String idempotencyKey,
            @Valid @RequestBody ManualLlmActionRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        return service.regenerate(
                snapshotId,
                principal.getUserId(),
                idempotencyKey,
                request
        );
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ManualLlmJobView cancel(
            @PathVariable UUID jobId,
            @RequestHeader(name = "Idempotency-Key", required = true)
            String idempotencyKey,
            @Valid @RequestBody ManualLlmActionRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        return service.cancel(jobId, principal.getUserId(), idempotencyKey, request);
    }
}

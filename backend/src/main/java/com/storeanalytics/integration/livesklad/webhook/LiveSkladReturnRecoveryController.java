package com.storeanalytics.integration.livesklad.webhook;

import com.storeanalytics.auth.security.AppUserPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/integrations/livesklad/returns/recoveries")
public class LiveSkladReturnRecoveryController {

    private final LiveSkladReturnRecoveryService service;

    public LiveSkladReturnRecoveryController(
            LiveSkladReturnRecoveryService service
    ) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    LiveSkladReturnRecoveryView request(
            @RequestHeader(name = "Idempotency-Key", required = true)
            String idempotencyKey,
            @Valid @RequestBody RecoverLiveSkladReturnRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        return service.request(
                principal.getUserId(),
                idempotencyKey,
                request.externalId(),
                request.expectedDocumentNumber(),
                request.expectedNetAmount(),
                request.expectedPositionCount(),
                request.reason()
        );
    }

    @GetMapping("/{recoveryId}")
    LiveSkladReturnRecoveryView get(@PathVariable UUID recoveryId) {
        return service.get(recoveryId);
    }
}

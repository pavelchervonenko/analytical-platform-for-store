package com.storeanalytics.notification.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.notification.operations.ManualTelegramResendRequest;
import com.storeanalytics.notification.operations.ManualTelegramResendService;
import com.storeanalytics.notification.operations.ManualTelegramResendView;
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
@RequestMapping("/api/admin/notifications/telegram/deliveries")
public class ManualTelegramResendController {

    private final ManualTelegramResendService resendService;

    public ManualTelegramResendController(ManualTelegramResendService resendService) {
        this.resendService = resendService;
    }

    @PostMapping("/{deliveryId}/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ManualTelegramResendView resend(
            @PathVariable UUID deliveryId,
            @RequestHeader(name = "Idempotency-Key", required = true)
            String idempotencyKey,
            @Valid @RequestBody ManualTelegramResendRequest request,
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        return resendService.resend(
                deliveryId,
                principal.getUserId(),
                idempotencyKey,
                request
        );
    }
}

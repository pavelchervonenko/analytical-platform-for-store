package com.storeanalytics.integration.livesklad.webhook;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/api/integrations/livesklad/webhooks")
class LiveSkladWebhookController {

    private final LiveSkladWebhookService service;

    LiveSkladWebhookController(LiveSkladWebhookService service) {
        this.service = service;
    }

    @PostMapping(
            path = "/sale-returns",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE
    )
    ResponseEntity<String> receiveSaleReturn(
            @RequestHeader(
                    name = LiveSkladWebhookService.VERIFICATION_HEADER,
                    required = false
            ) String verification,
            @RequestBody(required = false) byte[] body
    ) {
        return response(service.receive(
                LiveSkladWebhookKind.SALE_RETURN,
                body,
                verification
        ));
    }

    @PostMapping(
            path = "/order-returns",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE
    )
    ResponseEntity<String> receiveOrderReturn(
            @RequestHeader(
                    name = LiveSkladWebhookService.VERIFICATION_HEADER,
                    required = false
            ) String verification,
            @RequestBody(required = false) byte[] body
    ) {
        return response(service.receive(
                LiveSkladWebhookKind.ORDER_RETURN,
                body,
                verification
        ));
    }

    private ResponseEntity<String> response(
            LiveSkladWebhookAcceptance acceptance
    ) {
        HttpStatus status = acceptance.accepted()
                ? HttpStatus.OK
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_PLAIN)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(acceptance.responseBody());
    }
}

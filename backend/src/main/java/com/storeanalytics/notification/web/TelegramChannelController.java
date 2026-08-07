package com.storeanalytics.notification.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.notification.linking.TelegramChannelView;
import com.storeanalytics.notification.linking.TelegramDeliverySettingsRequest;
import com.storeanalytics.notification.linking.TelegramLinkCreatedView;
import com.storeanalytics.notification.linking.TelegramLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications/channels/telegram")
public class TelegramChannelController {

    private final TelegramLinkService service;

    public TelegramChannelController(TelegramLinkService service) {
        this.service = service;
    }

    @Operation(responses = @ApiResponse(
            responseCode = "200",
            useReturnTypeSchema = true,
            headers = @Header(
                    name = HttpHeaders.ETAG,
                    description = "Strong ETag when a subscription exists",
                    schema = @Schema(type = "string")
            )
    ))
    @GetMapping
    ResponseEntity<TelegramChannelView> get(
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        TelegramChannelView view = service.get(userId(principal));
        return channelResponse(view);
    }

    @Operation(responses = @ApiResponse(
            responseCode = "201",
            useReturnTypeSchema = true
    ))
    @PostMapping("/link")
    ResponseEntity<TelegramLinkCreatedView> createLink(
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        TelegramLinkCreatedView view = service.createLink(userId(principal));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(view);
    }

    @Operation(responses = @ApiResponse(
            responseCode = "200",
            useReturnTypeSchema = true,
            headers = @Header(
                    name = HttpHeaders.ETAG,
                    description = "Strong ETag for the confirmed subscription",
                    schema = @Schema(type = "string")
            )
    ))
    @PostMapping("/confirm")
    ResponseEntity<TelegramChannelView> confirm(
            @Parameter(
                    description = "Strong ETag returned for the pending subscription",
                    required = true
            )
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        return channelResponse(service.confirm(userId(principal), ifMatch));
    }

    @Operation(responses = @ApiResponse(
            responseCode = "200",
            useReturnTypeSchema = true
    ))
    @PostMapping("/revoke")
    ResponseEntity<TelegramChannelView> revoke(
            @Parameter(
                    description = "Strong ETag returned for the current subscription",
                    required = true
            )
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        return channelResponse(service.revoke(userId(principal), ifMatch));
    }

    @Operation(responses = @ApiResponse(
            responseCode = "200",
            useReturnTypeSchema = true,
            headers = @Header(
                    name = HttpHeaders.ETAG,
                    description = "Strong ETag for the updated subscription",
                    schema = @Schema(type = "string")
            )
    ))
    @PutMapping("/settings")
    ResponseEntity<TelegramChannelView> updateSettings(
            @Valid @RequestBody TelegramDeliverySettingsRequest request,
            @Parameter(
                    description = "Strong ETag returned for the active subscription",
                    required = true
            )
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @AuthenticationPrincipal AppUserPrincipal principal
    ) {
        return channelResponse(service.updateSettings(
                userId(principal),
                request,
                ifMatch
        ));
    }

    private ResponseEntity<TelegramChannelView> channelResponse(
            TelegramChannelView view
    ) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore());
        String etag = TelegramLinkService.etag(view);
        if (etag != null) {
            response.eTag(etag);
        }
        return response.body(view);
    }

    private UUID userId(AppUserPrincipal principal) {
        return principal.getUserId();
    }
}

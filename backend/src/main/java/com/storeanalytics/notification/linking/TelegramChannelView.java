package com.storeanalytics.notification.linking;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TelegramChannelView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        TelegramChannelState state,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        UUID subscriptionId,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        Long version,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        Instant linkExpiresAt,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        Instant pendingSince,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        Instant confirmedAt,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        Instant blockedAt,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        String destination,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        TelegramDeliverySettingsView deliverySettings,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<TelegramChannelAction> allowedActions,
        @Schema(
                format = "uri",
                nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String publicBotUrl
) {

    public TelegramChannelView {
        allowedActions = List.copyOf(allowedActions);
    }
}

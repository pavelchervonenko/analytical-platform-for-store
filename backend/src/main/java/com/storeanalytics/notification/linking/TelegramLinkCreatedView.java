package com.storeanalytics.notification.linking;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record TelegramLinkCreatedView(
        @Schema(format = "uri", requiredMode = Schema.RequiredMode.REQUIRED)
        String deepLink,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant expiresAt
) {
}

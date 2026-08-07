package com.storeanalytics.notification.linking;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;

public record TelegramDeliverySettingsView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String timezone,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean quietHoursEnabled,
        @Schema(type = "string", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalTime quietHoursStart,
        @Schema(type = "string", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalTime quietHoursEnd
) {
}

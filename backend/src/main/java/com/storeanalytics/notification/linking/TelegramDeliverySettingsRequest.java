package com.storeanalytics.notification.linking;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.time.ZoneId;

public record TelegramDeliverySettingsRequest(
        @NotBlank
        @Size(min = 1, max = 100)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String timezone,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean quietHoursEnabled,
        @NotNull
        @Schema(type = "string", format = "time", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalTime quietHoursStart,
        @NotNull
        @Schema(type = "string", format = "time", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalTime quietHoursEnd
) {

    public TelegramDeliverySettingsRequest {
        timezone = requireText(timezone, "timezone").trim();
        require(timezone.length() <= 100, "timezone must not exceed 100 characters");
        require(
                ZoneId.getAvailableZoneIds().contains(timezone),
                "timezone must be a supported IANA time-zone ID"
        );
        quietHoursStart = requireMinutePrecision(
                quietHoursStart,
                "quietHoursStart"
        );
        quietHoursEnd = requireMinutePrecision(
                quietHoursEnd,
                "quietHoursEnd"
        );
        require(
                !quietHoursEnabled || !quietHoursStart.equals(quietHoursEnd),
                "enabled quiet hours must have different start and end times"
        );
    }

    private static LocalTime requireMinutePrecision(
            LocalTime value,
            String fieldName
    ) {
        LocalTime time = requireNonNull(value, fieldName);
        require(
                time.getSecond() == 0 && time.getNano() == 0,
                fieldName + " must use minute precision"
        );
        return time;
    }
}

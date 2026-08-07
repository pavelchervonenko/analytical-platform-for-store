package com.storeanalytics.notification.linking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class TelegramDeliverySettingsRequestTest {

    @Test
    void acceptsSupportedIanaTimezoneAndOvernightQuietHours() {
        TelegramDeliverySettingsRequest request =
                new TelegramDeliverySettingsRequest(
                        "Europe/Moscow",
                        true,
                        LocalTime.of(22, 0),
                        LocalTime.of(7, 30)
                );

        assertThat(request.timezone()).isEqualTo("Europe/Moscow");
    }

    @Test
    void rejectsUnknownTimezoneAndAmbiguousAllDayWindow() {
        assertThatThrownBy(() -> new TelegramDeliverySettingsRequest(
                "Europe/Unknown",
                false,
                LocalTime.of(22, 0),
                LocalTime.of(7, 30)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TelegramDeliverySettingsRequest(
                "Europe/Moscow",
                true,
                LocalTime.of(8, 0),
                LocalTime.of(8, 0)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSubMinutePrecisionThatTheUiCannotRepresent() {
        assertThatThrownBy(() -> new TelegramDeliverySettingsRequest(
                "Europe/Moscow",
                true,
                LocalTime.of(22, 0, 30),
                LocalTime.of(7, 30)
        )).isInstanceOf(IllegalArgumentException.class);
    }
}

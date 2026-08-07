package com.storeanalytics.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TelegramNotificationPropertiesTest {

    private static final String CURRENT_SECRET =
            "current_webhook_secret_123456789";
    private static final String PREVIOUS_SECRET =
            "previous_webhook_secret_987654321";

    @Test
    void acceptsDistinctPreviousSecretForControlledRotation() {
        TelegramNotificationProperties properties = properties(
                CURRENT_SECRET,
                PREVIOUS_SECRET
        );

        assertThat(properties.webhookSecret()).isEqualTo(CURRENT_SECRET);
        assertThat(properties.webhookPreviousSecret()).isEqualTo(PREVIOUS_SECRET);
        assertThat(properties.toString())
                .doesNotContain(CURRENT_SECRET)
                .doesNotContain(PREVIOUS_SECRET);
    }

    @Test
    void rejectsMalformedOrRepeatedPreviousSecret() {
        assertThatThrownBy(() -> properties(CURRENT_SECRET, "too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("webhookPreviousSecret is invalid");
        assertThatThrownBy(() -> properties(CURRENT_SECRET, CURRENT_SECRET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ from webhookSecret");
    }

    @Test
    void rejectsActiveComponentWhenTelegramIsGloballyDisabled() {
        assertThatThrownBy(() -> properties(
                false,
                true,
                false,
                false,
                false,
                "",
                ""
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enabled for active components");
    }

    private TelegramNotificationProperties properties(
            String currentSecret,
            String previousSecret
    ) {
        return properties(
                true,
                false,
                false,
                true,
                false,
                currentSecret,
                previousSecret
        );
    }

    private TelegramNotificationProperties properties(
            boolean enabled,
            boolean fanoutEnabled,
            boolean linkingEnabled,
            boolean webhookEnabled,
            boolean deliveryEnabled,
            String currentSecret,
            String previousSecret
    ) {
        return new TelegramNotificationProperties(
                enabled,
                fanoutEnabled,
                "primary",
                Duration.ofSeconds(5),
                5,
                "weekly-telegram-v1",
                linkingEnabled,
                webhookEnabled,
                "",
                Duration.ofMinutes(10),
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                5,
                currentSecret,
                previousSecret,
                65_536,
                deliveryEnabled,
                "",
                "https://api.telegram.org",
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                Duration.ofSeconds(5),
                Duration.ofMinutes(1),
                Duration.ofSeconds(15),
                Duration.ofMinutes(5),
                65_536
        );
    }
}

package com.storeanalytics.integration.livesklad.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LiveSkladWebhookPropertiesTest {

    private static final String SALE_SECRET =
            "sale_return_secret_12345678901234567890";
    private static final String ORDER_SECRET =
            "order_return_secret_123456789012345678";

    @Test
    void allowsDisabledReceiverWithoutSecrets() {
        LiveSkladWebhookProperties properties = properties(
                false,
                "",
                "",
                "",
                "",
                262_144
        );

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.toString())
                .doesNotContain(SALE_SECRET, ORDER_SECRET)
                .contains("secrets=REDACTED");
    }

    @Test
    void requiresDistinctStrongSecretsWhenEnabled() {
        assertThatThrownBy(() -> properties(
                true,
                "short",
                "",
                ORDER_SECRET,
                "",
                262_144
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("saleReturnSecret");

        assertThatThrownBy(() -> properties(
                true,
                SALE_SECRET,
                "",
                SALE_SECRET,
                "",
                262_144
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void supportsPreviousSecretsForControlledRotation() {
        LiveSkladWebhookProperties properties = properties(
                true,
                SALE_SECRET,
                "previous_sale_secret_123456789012345",
                ORDER_SECRET,
                "previous_order_secret_12345678901234",
                262_144
        );

        assertThat(properties.currentSecret(LiveSkladWebhookKind.SALE_RETURN))
                .isEqualTo(SALE_SECRET);
        assertThat(properties.previousSecret(LiveSkladWebhookKind.ORDER_RETURN))
                .isEqualTo("previous_order_secret_12345678901234");
    }

    @Test
    void boundsMaximumBodySize() {
        assertThatThrownBy(() -> properties(
                false,
                "",
                "",
                "",
                "",
                1_048_577
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBodyBytes");
    }

    private LiveSkladWebhookProperties properties(
            boolean enabled,
            String saleSecret,
            String salePreviousSecret,
            String orderSecret,
            String orderPreviousSecret,
            int maxBodyBytes
    ) {
        return new LiveSkladWebhookProperties(
                enabled,
                saleSecret,
                salePreviousSecret,
                orderSecret,
                orderPreviousSecret,
                maxBodyBytes
        );
    }
}

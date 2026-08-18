package com.storeanalytics.integration.livesklad.webhook;

import static com.storeanalytics.common.validation.ModelValidation.require;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.livesklad.webhook")
public record LiveSkladWebhookProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String saleReturnSecret,
        @DefaultValue("") String saleReturnPreviousSecret,
        @DefaultValue("") String orderReturnSecret,
        @DefaultValue("") String orderReturnPreviousSecret,
        @DefaultValue("262144") int maxBodyBytes
) {

    private static final String SECRET_PATTERN = "[A-Za-z0-9_-]{32,256}";

    public LiveSkladWebhookProperties {
        require(maxBodyBytes >= 1024 && maxBodyBytes <= 1_048_576,
                "maxBodyBytes must be between 1024 and 1048576");
        validateOptionalSecret(saleReturnPreviousSecret, "saleReturnPreviousSecret");
        validateOptionalSecret(orderReturnPreviousSecret, "orderReturnPreviousSecret");
        if (enabled) {
            require(validSecret(saleReturnSecret),
                    "saleReturnSecret is invalid when webhook is enabled");
            require(validSecret(orderReturnSecret),
                    "orderReturnSecret is invalid when webhook is enabled");
            require(!saleReturnSecret.equals(orderReturnSecret),
                    "Sale and order return webhook secrets must differ");
        }
        require(!sameNonBlank(saleReturnSecret, saleReturnPreviousSecret),
                "saleReturnPreviousSecret must differ from saleReturnSecret");
        require(!sameNonBlank(orderReturnSecret, orderReturnPreviousSecret),
                "orderReturnPreviousSecret must differ from orderReturnSecret");
    }

    String currentSecret(LiveSkladWebhookKind kind) {
        return switch (kind) {
            case SALE_RETURN -> saleReturnSecret;
            case ORDER_RETURN -> orderReturnSecret;
        };
    }

    String previousSecret(LiveSkladWebhookKind kind) {
        return switch (kind) {
            case SALE_RETURN -> saleReturnPreviousSecret;
            case ORDER_RETURN -> orderReturnPreviousSecret;
        };
    }

    @Override
    public String toString() {
        return "LiveSkladWebhookProperties[enabled=" + enabled
                + ", maxBodyBytes=" + maxBodyBytes
                + ", secrets=REDACTED]";
    }

    private static void validateOptionalSecret(String value, String field) {
        if (value != null && !value.isBlank()) {
            require(validSecret(value), field + " is invalid");
        }
    }

    private static boolean validSecret(String value) {
        return value != null && value.matches(SECRET_PATTERN);
    }

    private static boolean sameNonBlank(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }
}

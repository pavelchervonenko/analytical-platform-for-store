package com.storeanalytics.notification.config;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.notification.telegram")
public record TelegramNotificationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean fanoutEnabled,
        @DefaultValue("store-analytics-primary") String botCode,
        @DefaultValue("5s") Duration fanoutDelay,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("weekly-telegram-v1") String renderVersion,
        @DefaultValue("false") boolean linkingEnabled,
        @DefaultValue("false") boolean webhookEnabled,
        @DefaultValue("") String botUsername,
        @DefaultValue("10m") Duration linkTokenTtl,
        @DefaultValue("10m") Duration pendingConfirmationTtl,
        @DefaultValue("30s") Duration linkIssueMinInterval,
        @DefaultValue("5") int linkMaxPerHour,
        @DefaultValue("") String webhookSecret,
        @DefaultValue("") String webhookPreviousSecret,
        @DefaultValue("65536") int webhookMaxBodyBytes,
        @DefaultValue("false") boolean deliveryEnabled,
        @DefaultValue("") String botToken,
        @DefaultValue("https://api.telegram.org") String apiBaseUrl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout,
        @DefaultValue("5s") Duration deliveryDelay,
        @DefaultValue("1m") Duration deliveryLeaseDuration,
        @DefaultValue("15s") Duration deliveryRetryInitialDelay,
        @DefaultValue("5m") Duration deliveryRetryMaxDelay,
        @DefaultValue("65536") int maxResponseBytes
) {

    public TelegramNotificationProperties {
        requireText(botCode, "botCode");
        requirePositive(fanoutDelay, "fanoutDelay");
        require(maxAttempts >= 1 && maxAttempts <= 20,
                "maxAttempts must be between 1 and 20");
        requireText(renderVersion, "renderVersion");
        requirePositive(linkTokenTtl, "linkTokenTtl");
        require(linkTokenTtl.compareTo(Duration.ofHours(1)) <= 0,
                "linkTokenTtl must not exceed one hour");
        requirePositive(pendingConfirmationTtl, "pendingConfirmationTtl");
        require(pendingConfirmationTtl.compareTo(Duration.ofHours(24)) <= 0,
                "pendingConfirmationTtl must not exceed 24 hours");
        requirePositive(linkIssueMinInterval, "linkIssueMinInterval");
        require(linkIssueMinInterval.compareTo(Duration.ofHours(1)) <= 0,
                "linkIssueMinInterval must not exceed one hour");
        require(linkMaxPerHour >= 1 && linkMaxPerHour <= 100,
                "linkMaxPerHour must be between 1 and 100");
        require(webhookMaxBodyBytes >= 1024 && webhookMaxBodyBytes <= 262_144,
                "webhookMaxBodyBytes must be between 1024 and 262144");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        requirePositive(deliveryDelay, "deliveryDelay");
        requirePositive(deliveryLeaseDuration, "deliveryLeaseDuration");
        require(deliveryLeaseDuration.compareTo(readTimeout) > 0,
                "deliveryLeaseDuration must exceed readTimeout");
        requirePositive(deliveryRetryInitialDelay, "deliveryRetryInitialDelay");
        requirePositive(deliveryRetryMaxDelay, "deliveryRetryMaxDelay");
        require(deliveryRetryMaxDelay.compareTo(deliveryRetryInitialDelay) >= 0,
                "deliveryRetryMaxDelay must not be shorter than initial delay");
        require(maxResponseBytes >= 1024 && maxResponseBytes <= 1_048_576,
                "maxResponseBytes must be between 1024 and 1048576");
        URI providerUri = URI.create(requireText(apiBaseUrl, "apiBaseUrl"));
        require(providerUri.getHost() != null, "apiBaseUrl must have a host");
        if (fanoutEnabled || linkingEnabled || webhookEnabled || deliveryEnabled) {
            require(enabled,
                    "Telegram notifications must be enabled for active components");
        }
        if (linkingEnabled) {
            require(botUsername != null
                            && botUsername.matches("[A-Za-z0-9_]{5,64}"),
                    "botUsername is invalid when linking is enabled");
        }
        if (webhookEnabled) {
            require(validWebhookSecret(webhookSecret),
                    "webhookSecret is invalid when webhook is enabled");
        }
        if (webhookPreviousSecret != null
                && !webhookPreviousSecret.isBlank()) {
            require(validWebhookSecret(webhookPreviousSecret),
                    "webhookPreviousSecret is invalid");
            require(!webhookPreviousSecret.equals(webhookSecret),
                    "webhookPreviousSecret must differ from webhookSecret");
        }
        if (deliveryEnabled) {
            require("https".equalsIgnoreCase(providerUri.getScheme()),
                    "apiBaseUrl must use HTTPS when delivery is enabled");
            require(validBotToken(botToken),
                    "botToken is invalid when delivery is enabled");
        }
    }

    public boolean isDeliveryConfigured() {
        return validBotToken(botToken);
    }

    @Override
    public String toString() {
        return "TelegramNotificationProperties[enabled=" + enabled
                + ", fanoutEnabled=" + fanoutEnabled
                + ", linkingEnabled=" + linkingEnabled
                + ", webhookEnabled=" + webhookEnabled
                + ", deliveryEnabled=" + deliveryEnabled
                + ", botCode=" + botCode
                + ", botUsernameConfigured="
                + (botUsername != null && !botUsername.isBlank())
                + ", webhookSecret=REDACTED, webhookPreviousSecret=REDACTED"
                + ", botToken=REDACTED]";
    }

    private static boolean validWebhookSecret(String value) {
        return value != null
                && value.matches("[A-Za-z0-9_-]{16,256}");
    }

    private static boolean validBotToken(String value) {
        return value != null
                && value.matches("[0-9]{6,20}:[A-Za-z0-9_-]{30,100}");
    }

    private static void requirePositive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isZero() && !duration.isNegative(),
                field + " must be positive");
    }
}

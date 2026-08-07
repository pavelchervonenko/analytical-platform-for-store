package com.storeanalytics.notification.delivery;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

public record TelegramSendReceipt(
        String providerMessageId,
        int httpStatus,
        long latencyMs
) {

    public TelegramSendReceipt {
        requireText(providerMessageId, "providerMessageId");
        require(httpStatus >= 200 && httpStatus < 300,
                "httpStatus must be successful");
        require(latencyMs >= 0, "latencyMs must not be negative");
    }
}

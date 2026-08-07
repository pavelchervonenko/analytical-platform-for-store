package com.storeanalytics.notification.delivery;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Instant;
import java.util.UUID;

public record TelegramSendRequest(
        UUID deliveryId,
        long chatId,
        String text,
        Instant deadline
) {

    public TelegramSendRequest {
        requireNonNull(deliveryId, "deliveryId");
        require(chatId != 0, "chatId must not be zero");
        requireText(text, "text");
        requireNonNull(deadline, "deadline");
    }
}

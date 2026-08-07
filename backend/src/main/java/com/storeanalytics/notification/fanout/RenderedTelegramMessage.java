package com.storeanalytics.notification.fanout;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

public record RenderedTelegramMessage(String text, String contentHash) {

    public RenderedTelegramMessage {
        requireText(text, "text");
        require(text.codePointCount(0, text.length()) <= 4096,
                "Telegram text must not exceed 4096 characters");
        require(contentHash != null && contentHash.matches("[a-f0-9]{64}"),
                "contentHash must be a lowercase SHA-256");
    }
}

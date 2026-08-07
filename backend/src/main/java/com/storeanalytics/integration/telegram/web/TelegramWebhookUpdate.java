package com.storeanalytics.integration.telegram.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramWebhookUpdate(
        @JsonProperty("update_id") Long updateId,
        TelegramMessage message,
        @JsonProperty("my_chat_member") TelegramChatMemberUpdated myChatMember
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramMessage(
            TelegramUser from,
            TelegramChat chat,
            String text
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramUser(
            Long id,
            @JsonProperty("is_bot") Boolean bot
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramChat(Long id, String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramChatMemberUpdated(
            TelegramChat chat,
            @JsonProperty("old_chat_member") TelegramChatMember oldChatMember,
            @JsonProperty("new_chat_member") TelegramChatMember newChatMember
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramChatMember(String status) {
    }
}

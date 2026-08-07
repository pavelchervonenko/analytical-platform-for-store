package com.storeanalytics.notification.linking;

public record TelegramUpdateCommand(
        long updateId,
        TelegramMessageCommand message,
        TelegramMembershipCommand membership
) {

    public record TelegramMessageCommand(
            long telegramUserId,
            boolean senderBot,
            long chatId,
            String chatType,
            String text
    ) {
    }

    public record TelegramMembershipCommand(
            long chatId,
            String chatType,
            String oldStatus,
            String newStatus
    ) {
    }
}

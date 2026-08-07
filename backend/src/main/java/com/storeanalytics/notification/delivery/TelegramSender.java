package com.storeanalytics.notification.delivery;

public interface TelegramSender {

    TelegramSendReceipt send(TelegramSendRequest request);
}

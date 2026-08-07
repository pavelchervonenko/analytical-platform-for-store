package com.storeanalytics.notification.operations;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

public class TelegramDeliveryNotFoundException extends BusinessException {

    public TelegramDeliveryNotFoundException(UUID deliveryId) {
        super(
                BusinessErrorCode.TELEGRAM_DELIVERY_NOT_FOUND,
                "Telegram delivery was not found: " + deliveryId
        );
    }
}

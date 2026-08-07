package com.storeanalytics.notification.operations;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class TelegramDeliveryResendConflictException extends BusinessException {

    public TelegramDeliveryResendConflictException(String internalMessage) {
        super(BusinessErrorCode.TELEGRAM_DELIVERY_RESEND_CONFLICT, internalMessage);
    }

    public TelegramDeliveryResendConflictException(
            String internalMessage,
            Throwable cause
    ) {
        super(BusinessErrorCode.TELEGRAM_DELIVERY_RESEND_CONFLICT, internalMessage, cause);
    }
}

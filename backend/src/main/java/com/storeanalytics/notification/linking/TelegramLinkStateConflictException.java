package com.storeanalytics.notification.linking;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class TelegramLinkStateConflictException extends BusinessException {

    public TelegramLinkStateConflictException(String internalMessage) {
        super(BusinessErrorCode.TELEGRAM_LINK_STATE_CONFLICT, internalMessage);
    }
}

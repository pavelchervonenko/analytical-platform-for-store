package com.storeanalytics.notification.linking;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class TelegramLinkThrottledException extends BusinessException {

    public TelegramLinkThrottledException(String internalMessage) {
        super(BusinessErrorCode.TELEGRAM_LINK_THROTTLED, internalMessage);
    }
}

package com.storeanalytics.auth.exception;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class CurrentSessionRequiresLogoutException extends BusinessException {

    public CurrentSessionRequiresLogoutException() {
        super(
                BusinessErrorCode.CURRENT_SESSION_REQUIRES_LOGOUT,
                "Current browser session must use the logout endpoint"
        );
    }
}

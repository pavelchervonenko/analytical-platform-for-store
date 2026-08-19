package com.storeanalytics.integration.livesklad.webhook;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;
import java.util.UUID;

class LiveSkladReturnRecoveryNotFoundException extends BusinessException {

    LiveSkladReturnRecoveryNotFoundException(UUID recoveryId) {
        super(
                BusinessErrorCode.RETURN_RECOVERY_NOT_FOUND,
                "LiveSklad return recovery does not exist: " + recoveryId
        );
    }
}

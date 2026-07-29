package com.storeanalytics.common.idempotency;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class IdempotencyKeyConflictException extends BusinessException {

    public IdempotencyKeyConflictException() {
        super(
                BusinessErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Idempotency key action, resource, body hash, or response type does not match"
        );
    }
}

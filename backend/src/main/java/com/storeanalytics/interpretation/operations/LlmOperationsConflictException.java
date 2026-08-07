package com.storeanalytics.interpretation.operations;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class LlmOperationsConflictException extends BusinessException {
    public LlmOperationsConflictException(String message) {
        super(BusinessErrorCode.LLM_OPERATIONS_CONFLICT, message);
    }
}

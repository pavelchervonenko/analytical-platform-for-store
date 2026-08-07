package com.storeanalytics.interpretation.operations;

import com.storeanalytics.common.exception.BusinessErrorCode;
import com.storeanalytics.common.exception.BusinessException;

public class LlmOperationsNotFoundException extends BusinessException {
    public LlmOperationsNotFoundException(String message) {
        super(BusinessErrorCode.LLM_JOB_NOT_FOUND, message);
    }
}

package com.storeanalytics.metrics.cases;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CaseAttachDecisionRequest(
        @NotBlank String targetCode,
        @NotBlank @Size(max = 1000) String reason
) { }

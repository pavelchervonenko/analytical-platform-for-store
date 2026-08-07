package com.storeanalytics.interpretation.operations;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ManualLlmActionRequest(
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}

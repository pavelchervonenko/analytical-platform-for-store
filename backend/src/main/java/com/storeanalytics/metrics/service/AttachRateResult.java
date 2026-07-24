package com.storeanalytics.metrics.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AttachRateResult(
        UUID storeId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate periodStart,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate periodEnd,
        String formulaVersion,
        AttachRateDataQuality dataQuality,
        List<AttachRateEntry> rates
) {
}

package com.storeanalytics.performance.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;

public record StorePlanDailyTargetView(
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate date,
        boolean completed,
        BigDecimal revenueBasisAmount,
        boolean revenueBasisProjected,
        StorePlanDailyDirectionView accessory,
        StorePlanDailyDirectionView service
) {
}

package com.storeanalytics.performance.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record WorkScheduleDayView(
        UUID storeId,
        LocalDate workDate,
        long revision,
        List<EmployeeShiftView> shifts
) {
}

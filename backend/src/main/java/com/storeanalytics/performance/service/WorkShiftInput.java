package com.storeanalytics.performance.service;

import java.math.BigDecimal;
import java.util.UUID;

public record WorkShiftInput(UUID employeeId, BigDecimal workedHours) {
}

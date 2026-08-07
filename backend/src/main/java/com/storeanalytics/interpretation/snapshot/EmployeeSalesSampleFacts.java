package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.util.Map;
import java.util.UUID;

public record EmployeeSalesSampleFacts(Map<UUID, Long> completedSalesByEmployee) {

    public EmployeeSalesSampleFacts {
        completedSalesByEmployee = Map.copyOf(requireNonNull(
                completedSalesByEmployee,
                "completedSalesByEmployee"
        ));
        completedSalesByEmployee.forEach((employeeId, count) -> {
            requireNonNull(employeeId, "employeeId");
            requireNonNull(count, "completedSales");
            if (count < 0) {
                throw new IllegalArgumentException("completedSales must not be negative");
            }
        });
    }

    public long completedSales(UUID employeeId) {
        return completedSalesByEmployee.getOrDefault(
                requireNonNull(employeeId, "employeeId"),
                0L
        );
    }
}

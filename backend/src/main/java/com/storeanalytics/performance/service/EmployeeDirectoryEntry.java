package com.storeanalytics.performance.service;

public record EmployeeDirectoryEntry(
        EmployeeRatingEntry current,
        EmployeeRatingDynamics dynamics
) {
}

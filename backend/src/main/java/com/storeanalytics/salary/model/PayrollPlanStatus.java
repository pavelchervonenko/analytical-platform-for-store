package com.storeanalytics.salary.model;

public record PayrollPlanStatus(
        boolean revenueAchieved,
        boolean accessoryAchieved,
        boolean serviceAchieved
) {
}

package com.storeanalytics.performance.web;

import com.storeanalytics.performance.model.EmployeeWorkShift;
import com.storeanalytics.performance.service.WorkShiftInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record WorkScheduleRequest(
        @Valid List<WorkScheduleShiftRequest> shifts,
        Set<UUID> employeeIds
) {

    @AssertTrue(message = "provide exactly one of shifts or employeeIds")
    public boolean isValidShape() {
        return (shifts == null) != (employeeIds == null);
    }

    public List<WorkShiftInput> toInputs() {
        if (shifts != null) {
            return shifts.stream()
                    .map(shift -> new WorkShiftInput(
                            shift.employeeId(), shift.workedHours()
                    ))
                    .toList();
        }
        return employeeIds.stream()
                .map(employeeId -> new WorkShiftInput(
                        employeeId, EmployeeWorkShift.FULL_SHIFT_HOURS
                ))
                .toList();
    }
}

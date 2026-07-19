package com.storeanalytics.sync.service;

import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncStatus;
import java.util.UUID;

public record EmployeeSyncResult(
        UUID syncRunId,
        SyncStatus status,
        int recordsFetched,
        int recordsCreated,
        int recordsUpdated,
        int recordsSkipped,
        int recordsFailed,
        int assignmentsDeactivated,
        int employeesDeactivated
) {

    public static EmployeeSyncResult from(
            SyncRun run,
            int assignmentsDeactivated,
            int employeesDeactivated
    ) {
        return new EmployeeSyncResult(
                run.getId(),
                run.getStatus(),
                run.getRecordsFetched(),
                run.getRecordsCreated(),
                run.getRecordsUpdated(),
                run.getRecordsSkipped(),
                run.getRecordsFailed(),
                assignmentsDeactivated,
                employeesDeactivated
        );
    }
}

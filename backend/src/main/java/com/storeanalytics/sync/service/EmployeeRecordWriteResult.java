package com.storeanalytics.sync.service;

import java.util.UUID;

record EmployeeRecordWriteResult(
        UUID employeeId,
        StoreWriteResult outcome
) {
}

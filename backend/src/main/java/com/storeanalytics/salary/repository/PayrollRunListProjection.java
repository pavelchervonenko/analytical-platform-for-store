package com.storeanalytics.salary.repository;

import com.storeanalytics.salary.model.PayrollRunStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface PayrollRunListProjection {

    UUID getId();

    UUID getStoreId();

    LocalDate getPeriodMonth();

    int getRevision();

    UUID getSupersedesRunId();

    String getRevisionReason();

    PayrollRunStatus getStatus();

    Instant getCreatedAt();
}

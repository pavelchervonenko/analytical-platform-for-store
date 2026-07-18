package com.storeanalytics.metrics.repository;

import com.storeanalytics.metrics.model.ReportSnapshot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportSnapshotRepository extends JpaRepository<ReportSnapshot, UUID> {
}

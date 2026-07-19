package com.storeanalytics.sync.repository;

import com.storeanalytics.sync.model.SyncRun;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncRunRepository extends JpaRepository<SyncRun, UUID> {
}

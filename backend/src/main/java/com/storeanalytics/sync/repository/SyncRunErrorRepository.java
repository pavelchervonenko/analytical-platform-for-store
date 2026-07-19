package com.storeanalytics.sync.repository;

import com.storeanalytics.sync.model.SyncRunError;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncRunErrorRepository extends JpaRepository<SyncRunError, UUID> {
}

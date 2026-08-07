package com.storeanalytics.sync.repository;

import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncRunRepository extends JpaRepository<SyncRun, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select run from SyncRun run
            where run.syncJobId = :syncJobId
              and run.status = :status
            order by run.startedAt, run.id
            """)
    List<SyncRun> findAllByJobIdAndStatusForUpdate(
            @Param("syncJobId") UUID syncJobId,
            @Param("status") SyncStatus status
    );
}

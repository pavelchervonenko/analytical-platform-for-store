package com.storeanalytics.sync.repository;

import com.storeanalytics.sync.model.SyncJob;
import com.storeanalytics.sync.model.SyncJobStatus;
import com.storeanalytics.sync.model.SyncJobType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncJobRepository extends JpaRepository<SyncJob, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from SyncJob job
            where job.status in :statuses
              and job.nextAttemptAt <= :now
            order by job.nextAttemptAt, job.createdAt
            """)
    List<SyncJob> findClaimable(
            @Param("statuses") Collection<SyncJobStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from SyncJob job
            where job.status = :status
              and job.leaseUntil < :now
            order by job.leaseUntil
            """)
    List<SyncJob> findExpiredLeases(
            @Param("status") SyncJobStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from SyncJob job where job.id = :id")
    Optional<SyncJob> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByConnectionIdAndStatusIn(
            UUID connectionId,
            Collection<SyncJobStatus> statuses
    );

    boolean existsByConnectionIdAndJobTypeAndPeriodStartAndPeriodEnd(
            UUID connectionId,
            SyncJobType jobType,
            Instant periodStart,
            Instant periodEnd
    );

    List<SyncJob> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

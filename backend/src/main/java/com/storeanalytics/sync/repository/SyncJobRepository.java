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
    @Query("select job from SyncJob job where job.connection.id = :connection and job.status in :statuses")
    List<SyncJob> findActiveForUpdate(@Param("connection") UUID connection,
                                    @Param("statuses") Collection<SyncJobStatus> statuses);

    @Query("""
            select job from SyncJob job where job.connection.id = :connection
              and job.jobType = com.storeanalytics.sync.model.SyncJobType.BACKFILL
              and job.status = com.storeanalytics.sync.model.SyncJobStatus.SUCCESS
              and job.requestedBy is not null and job.periodStart <= :start and job.periodEnd >= :end
              and job.finishedAt > :after order by job.finishedAt desc
            """)
    List<SyncJob> findCoveringManualBackfill(@Param("connection") UUID connection,
                                           @Param("start") Instant start, @Param("end") Instant end,
                                           @Param("after") Instant after, Pageable pageable);

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

    Optional<SyncJob> findFirstByConnectionIdAndJobTypeAndPeriodStartAndPeriodEndOrderByCreatedAtDesc(
            UUID connectionId,
            SyncJobType jobType,
            Instant periodStart,
            Instant periodEnd
    );

    long countByStatus(SyncJobStatus status);

    @Query("""
            select count(job) from SyncJob job
            where job.status = :status
              and job.leaseUntil < :now
            """)
    long countExpiredLeases(
            @Param("status") SyncJobStatus status,
            @Param("now") Instant now
    );

    List<SyncJob> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

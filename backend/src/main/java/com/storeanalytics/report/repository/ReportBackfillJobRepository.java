package com.storeanalytics.report.repository;

import com.storeanalytics.report.model.ReportBackfillJob;
import com.storeanalytics.report.model.ReportBackfillJobStatus;
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

public interface ReportBackfillJobRepository
        extends JpaRepository<ReportBackfillJob, UUID> {

    @Query(value = """
            SELECT *
            FROM report_backfill_jobs
            WHERE status IN ('PENDING', 'WAITING_RETRY')
              AND next_attempt_at <= :now
            ORDER BY next_attempt_at, created_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ReportBackfillJob> findClaimable(@Param("now") Instant now);

    @Query(value = """
            SELECT *
            FROM report_backfill_jobs
            WHERE status = 'RUNNING'
              AND lease_until < :now
            ORDER BY lease_until
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ReportBackfillJob> findExpiredLease(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from ReportBackfillJob job where job.id = :id")
    Optional<ReportBackfillJob> findByIdForUpdate(@Param("id") UUID id);

    Optional<ReportBackfillJob> findByRequestedByIdAndIdempotencyKey(
            UUID requestedById,
            String idempotencyKey
    );

    boolean existsByStoreIdAndStatusIn(
            UUID storeId,
            Collection<ReportBackfillJobStatus> statuses
    );

    long countByStatusIn(Collection<ReportBackfillJobStatus> statuses);

    long countByStatus(ReportBackfillJobStatus status);

    @Query("""
            select count(job) from ReportBackfillJob job
            where job.status = :status
              and job.leaseUntil < :now
            """)
    long countExpiredLeases(
            @Param("status") ReportBackfillJobStatus status,
            @Param("now") Instant now
    );

    List<ReportBackfillJob> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

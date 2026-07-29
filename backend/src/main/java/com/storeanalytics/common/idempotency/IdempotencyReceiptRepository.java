package com.storeanalytics.common.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyReceiptRepository
        extends JpaRepository<IdempotencyReceipt, UUID> {

    Optional<IdempotencyReceipt> findByActorIdAndIdempotencyKey(
            UUID actorId,
            String idempotencyKey
    );

    @Modifying
    @Query(value = """
            DELETE FROM idempotency_receipts
            WHERE id IN (
                SELECT id
                FROM idempotency_receipts
                WHERE expires_at <= :now
                ORDER BY expires_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            """, nativeQuery = true)
    int deleteExpiredBatch(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );
}

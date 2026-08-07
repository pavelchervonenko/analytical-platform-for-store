package com.storeanalytics.product.repository;

import com.storeanalytics.product.model.ProductCategoryAssignment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductCategoryAssignmentRepository
        extends JpaRepository<ProductCategoryAssignment, UUID> {

    @Query("""
            SELECT count(assignment)
            FROM ProductCategoryAssignment assignment
            WHERE assignment.product.connection.id = :connectionId
            """)
    long countByConnectionId(@Param("connectionId") UUID connectionId);

    @Query("""
            SELECT count(assignment)
            FROM ProductCategoryAssignment assignment
            WHERE assignment.product.connection.id = :connectionId
              AND assignment.validFrom <= :occurredAt
              AND (assignment.validTo IS NULL OR assignment.validTo > :occurredAt)
            """)
    long countEffectiveByConnectionId(
            @Param("connectionId") UUID connectionId,
            @Param("occurredAt") Instant occurredAt
    );

    @Query("""
            SELECT assignment
            FROM ProductCategoryAssignment assignment
            WHERE assignment.product.id = :productId
              AND assignment.validFrom <= :occurredAt
              AND (assignment.validTo IS NULL OR assignment.validTo > :occurredAt)
            ORDER BY assignment.validFrom DESC
            """)
    List<ProductCategoryAssignment> findEffectiveAssignments(
            @Param("productId") UUID productId,
            @Param("occurredAt") Instant occurredAt
    );
}

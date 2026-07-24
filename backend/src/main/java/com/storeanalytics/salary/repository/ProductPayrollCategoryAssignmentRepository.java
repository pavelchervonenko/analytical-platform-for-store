package com.storeanalytics.salary.repository;

import com.storeanalytics.salary.model.ProductPayrollCategoryAssignment;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPayrollCategoryAssignmentRepository
        extends JpaRepository<ProductPayrollCategoryAssignment, UUID> {

    Optional<ProductPayrollCategoryAssignment> findFirstByProductIdAndValidToIsNull(
            UUID productId
    );

    List<ProductPayrollCategoryAssignment> findAllByProductIdOrderByValidFromDesc(
            UUID productId
    );

    boolean existsByProductIdAndValidFromLessThanAndValidToGreaterThan(
            UUID productId,
            LocalDate validTo,
            LocalDate validFrom
    );
}

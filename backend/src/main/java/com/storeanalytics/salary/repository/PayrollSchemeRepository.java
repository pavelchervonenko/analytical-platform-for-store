package com.storeanalytics.salary.repository;

import com.storeanalytics.salary.model.PayrollScheme;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayrollSchemeRepository extends JpaRepository<PayrollScheme, UUID> {

    Optional<PayrollScheme> findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            LocalDate date
    );

    Optional<PayrollScheme> findFirstByOrderByEffectiveFromDesc();

    Page<PayrollScheme> findAllByOrderByEffectiveFromDescIdDesc(Pageable pageable);

    boolean existsByCode(String code);

    boolean existsByEffectiveFrom(LocalDate effectiveFrom);
}

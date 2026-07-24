package com.storeanalytics.salary.repository;

import com.storeanalytics.salary.model.PayrollScheme;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayrollSchemeRepository extends JpaRepository<PayrollScheme, UUID> {

    Optional<PayrollScheme> findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            LocalDate date
    );

    List<PayrollScheme> findAllByOrderByEffectiveFromDesc();

    boolean existsByCode(String code);

    boolean existsByEffectiveFrom(LocalDate effectiveFrom);
}

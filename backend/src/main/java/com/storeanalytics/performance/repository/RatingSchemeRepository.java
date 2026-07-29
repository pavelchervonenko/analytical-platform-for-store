package com.storeanalytics.performance.repository;

import com.storeanalytics.performance.model.RatingScheme;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RatingSchemeRepository extends JpaRepository<RatingScheme, UUID> {

    Optional<RatingScheme> findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            LocalDate effectiveDate
    );

    Page<RatingScheme> findAllByOrderByEffectiveFromDescIdDesc(Pageable pageable);

    boolean existsByCode(String code);

    boolean existsByEffectiveFrom(LocalDate effectiveFrom);
}

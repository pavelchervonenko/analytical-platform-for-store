package com.storeanalytics.performance.repository;

import com.storeanalytics.performance.model.RatingScheme;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RatingSchemeRepository extends JpaRepository<RatingScheme, UUID> {

    Optional<RatingScheme> findFirstByEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            LocalDate effectiveDate
    );

    List<RatingScheme> findAllByOrderByEffectiveFromDesc();

    boolean existsByCode(String code);

    boolean existsByEffectiveFrom(LocalDate effectiveFrom);
}

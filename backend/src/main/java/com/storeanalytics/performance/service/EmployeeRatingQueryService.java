package com.storeanalytics.performance.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.repository.EmployeeRatingSnapshotRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeRatingQueryService {

    private final EmployeeRatingService ratingService;
    private final EmployeeRatingSnapshotRepository snapshotRepository;
    private final EmployeeRatingSnapshotCodec snapshotCodec;

    public EmployeeRatingQueryService(
            EmployeeRatingService ratingService,
            EmployeeRatingSnapshotRepository snapshotRepository,
            EmployeeRatingSnapshotCodec snapshotCodec
    ) {
        this.ratingService = ratingService;
        this.snapshotRepository = snapshotRepository;
        this.snapshotCodec = snapshotCodec;
    }

    @Transactional(readOnly = true)
    public EmployeeRatingResult get(UUID storeId, StoreKpiPeriod period) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        StoreKpiPeriod validatedPeriod = requireNonNull(period, "period");
        return snapshotRepository.findByStoreIdAndPeriodStartAndPeriodEnd(
                validatedStoreId, validatedPeriod.start(), validatedPeriod.end()
        ).map(snapshotCodec::decode).orElseGet(
                () -> ratingService.calculate(validatedStoreId, validatedPeriod)
        );
    }
}

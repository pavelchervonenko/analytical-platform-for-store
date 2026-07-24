package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.AttachRateAggregate;
import com.storeanalytics.metrics.repository.AttachRateRepository;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttachRateService {

    static final String FORMULA_VERSION = "attach-rate-v1";
    private static final int QUANTITY_SCALE = 3;
    private static final int PERCENT_SCALE = 1;

    private final StoreRepository storeRepository;
    private final AttachRateRepository attachRateRepository;

    public AttachRateService(
            StoreRepository storeRepository,
            AttachRateRepository attachRateRepository
    ) {
        this.storeRepository = storeRepository;
        this.attachRateRepository = attachRateRepository;
    }

    @Transactional(readOnly = true)
    public AttachRateResult calculate(UUID storeId, StoreKpiPeriod period) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        StoreKpiPeriod validatedPeriod = requireNonNull(period, "period");
        if (!storeRepository.existsById(validatedStoreId)) {
            throw new StoreNotFoundException(validatedStoreId);
        }

        List<AttachRateAggregate> aggregates = attachRateRepository.aggregate(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end()
        );
        List<AttachRateEntry> rates = aggregates.stream()
                .map(this::toEntry)
                .toList();
        AttachRateDataQuality dataQuality = aggregates.isEmpty()
                ? new AttachRateDataQuality(0, 0, 0)
                : quality(aggregates.getFirst());
        return new AttachRateResult(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end(),
                FORMULA_VERSION,
                dataQuality,
                rates
        );
    }

    private AttachRateEntry toEntry(AttachRateAggregate aggregate) {
        BigDecimal numerator = quantity(aggregate.numeratorQuantity());
        BigDecimal denominator = quantity(aggregate.denominatorQuantity());
        BigDecimal rate = denominator.signum() <= 0
                ? null
                : numerator.multiply(BigDecimal.valueOf(100))
                        .divide(denominator, PERCENT_SCALE, RoundingMode.HALF_UP);
        return new AttachRateEntry(
                aggregate.metricCode(),
                aggregate.numeratorCategoryCode(),
                aggregate.denominatorCode(),
                numerator,
                denominator,
                rate
        );
    }

    private AttachRateDataQuality quality(AttachRateAggregate aggregate) {
        return new AttachRateDataQuality(
                aggregate.unmatchedNumeratorItemCount(),
                aggregate.ambiguousWarrantyItemCount(),
                aggregate.unknownDeviceConditionItemCount()
        );
    }

    private BigDecimal quantity(BigDecimal value) {
        return value.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }
}

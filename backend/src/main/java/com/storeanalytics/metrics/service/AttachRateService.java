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

    static final String FORMULA_VERSION = "attach-rate-v3";
    private static final int QUANTITY_SCALE = 3;
    private static final int PERCENT_SCALE = 2;

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
        return project(validatedStoreId, validatedPeriod,
                attachRateRepository.attributionEnabled() ? "attach-rate-v4" : FORMULA_VERSION,
                aggregates);
    }

    /** Applies one established rate/clamp/rounding formula to store or selected-seller aggregates. */
    public static AttachRateResult project(
            UUID storeId,
            StoreKpiPeriod period,
            String formulaVersion,
            List<AttachRateAggregate> aggregates
    ) {
        requireNonNull(storeId, "storeId");
        requireNonNull(period, "period");
        requireNonNull(formulaVersion, "formulaVersion");
        List<AttachRateAggregate> values = List.copyOf(requireNonNull(aggregates, "aggregates"));
        List<AttachRateEntry> rates = values.stream()
                .map(AttachRateService::toEntry)
                .toList();
        AttachRateDataQuality dataQuality = values.isEmpty()
                ? new AttachRateDataQuality(0, 0, 0)
                : quality(values.getFirst());
        return new AttachRateResult(
                storeId,
                period.start(),
                period.end(),
                formulaVersion,
                dataQuality,
                rates
        );
    }

    private static AttachRateEntry toEntry(AttachRateAggregate aggregate) {
        BigDecimal numerator = quantity(aggregate.numeratorReceiptCount());
        BigDecimal denominator = quantity(aggregate.denominatorReceiptCount());
        BigDecimal rate = denominator.signum() <= 0
                ? null
                : numerator.max(BigDecimal.ZERO)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(denominator, PERCENT_SCALE, RoundingMode.HALF_UP);
        return new AttachRateEntry(
                aggregate.metricCode(),
                aggregate.numeratorCategoryCode(),
                aggregate.denominatorCode(),
                numerator,
                denominator,
                rate,
                aggregate.preliminary()
        );
    }

    private static AttachRateDataQuality quality(AttachRateAggregate aggregate) {
        return new AttachRateDataQuality(
                aggregate.unmatchedNumeratorItemCount(),
                aggregate.ambiguousWarrantyItemCount(),
                aggregate.unknownDeviceConditionItemCount(),
                aggregate.unassignedReturnItemCount()
        );
    }

    private static BigDecimal quantity(BigDecimal value) {
        return value.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }
}

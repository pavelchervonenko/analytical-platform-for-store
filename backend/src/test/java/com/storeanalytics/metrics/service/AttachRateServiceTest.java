package com.storeanalytics.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.AttachRateAggregate;
import com.storeanalytics.metrics.repository.AttachRateRepository;
import com.storeanalytics.product.model.AttachDenominatorCode;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttachRateServiceTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    private StoreRepository storeRepository;
    private AttachRateRepository attachRateRepository;
    private AttachRateService service;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        attachRateRepository = mock(AttachRateRepository.class);
        service = new AttachRateService(storeRepository, attachRateRepository);
    }

    @Test
    void calculatesRatePerHundredAndPreservesValuesAboveOneHundred() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(attachRateRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(
                        aggregate("2.000", "3.000", 1, 2, 3),
                        new AttachRateAggregate(
                                "CASE_SAMSUNG",
                                "CASE_SAMSUNG",
                                AttachDenominatorCode.SAMSUNG,
                                new BigDecimal("3.000"),
                                new BigDecimal("2.000"),
                                1,
                                2,
                                3
                        )
                ));

        AttachRateResult result = service.calculate(storeId, period());

        assertThat(result.formulaVersion()).isEqualTo("attach-rate-v1");
        assertThat(result.rates()).hasSize(2);
        assertThat(result.rates().get(0).ratePerHundred()).isEqualByComparingTo("66.7");
        assertThat(result.rates().get(1).ratePerHundred()).isEqualByComparingTo("150.0");
        assertThat(result.dataQuality())
                .isEqualTo(new AttachRateDataQuality(1, 2, 3));
    }

    @Test
    void returnsNoRateForZeroOrNegativeDenominator() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(attachRateRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(
                        aggregate("0.000", "0.000", 0, 0, 0),
                        aggregate("1.000", "-1.000", 0, 0, 0)
                ));

        AttachRateResult result = service.calculate(storeId, period());

        assertThat(result.rates()).allSatisfy(entry ->
                assertThat(entry.ratePerHundred()).isNull());
    }

    @Test
    void rejectsUnknownStoreBeforeRunningAttachQuery() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(false);

        assertThatThrownBy(() -> service.calculate(storeId, period()))
                .isInstanceOf(StoreNotFoundException.class)
                .hasMessageContaining(storeId.toString());
        verifyNoInteractions(attachRateRepository);
    }

    private AttachRateAggregate aggregate(
            String numerator,
            String denominator,
            long unmatched,
            long ambiguous,
            long unknownCondition
    ) {
        return new AttachRateAggregate(
                "CASE_APPLE_IPHONE",
                "CASE_APPLE_IPHONE",
                AttachDenominatorCode.IPHONE,
                new BigDecimal(numerator),
                new BigDecimal(denominator),
                unmatched,
                ambiguous,
                unknownCondition
        );
    }

    private StoreKpiPeriod period() {
        return new StoreKpiPeriod(PERIOD_START, PERIOD_END);
    }
}

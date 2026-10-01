package com.storeanalytics.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.AttachDenominatorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AttachRateAggregateTest {

    @Test
    void unknownStoreReturnLimitsOnlyPotentiallyAffectedSellerMetric() {
        AttachRateAggregate seller = aggregate("2", "3", 1, 1, false, 0, 0);
        AttachRateAggregate storeRisk = aggregate("99", "100", 5, 6, false, 2, 1);

        AttachRateAggregate result = seller.withPotentialStoreAttributionRisk(storeRisk);

        assertThat(result.numeratorReceiptCount()).isEqualByComparingTo("2");
        assertThat(result.denominatorReceiptCount()).isEqualByComparingTo("3");
        assertThat(result.unmatchedNumeratorItemCount()).isOne();
        assertThat(result.unknownDeviceConditionItemCount()).isOne();
        assertThat(result.preliminary()).isTrue();
        assertThat(result.unassignedReturnItemCount()).isEqualTo(2);
        assertThat(result.unassignedMetricReturnItemCount()).isOne();
        assertThat(seller.withPotentialStoreAttributionRisk(
                aggregate("99", "100", 5, 6, false, 2, 0)).preliminary()).isFalse();
    }

    @Test
    void unresolvedWarrantyRiskRemainsPreliminaryWithoutChangingSellerQuantities() {
        AttachRateAggregate seller = aggregate("1", "4", 0, 0, false, 0, 0);
        AttachRateAggregate storeRisk = aggregate("8", "9", 0, 0, true, 0, 0);

        AttachRateAggregate result = seller.withPotentialStoreAttributionRisk(storeRisk);

        assertThat(result.preliminary()).isTrue();
        assertThat(result.numeratorReceiptCount()).isEqualByComparingTo("1");
        assertThat(result.denominatorReceiptCount()).isEqualByComparingTo("4");
    }

    @Test
    void catalogUncertaintySurvivesAnOtherwiseCleanStoreQualityProjection() {
        var seller = aggregate("1", "4", 0, 0, true, 0, 0);
        assertThat(seller.withPotentialStoreAttributionRisk(
                new AttachAttributionQuality("CASE_SAMSUNG", 0, 0, 0)).preliminary()).isTrue();
        assertThat(seller.withPotentialStoreAttributionRisk(
                aggregate("5", "10", 0, 0, false, 0, 0)).preliminary()).isTrue();
    }

    private AttachRateAggregate aggregate(String numerator, String denominator, long unmatched,
                                           long unknownCondition, boolean preliminary,
                                           long unassignedReturn, long unassignedMetricReturn) {
        return new AttachRateAggregate("CASE_SAMSUNG", "CASE_SAMSUNG", AttachDenominatorCode.SAMSUNG,
                new BigDecimal(numerator), new BigDecimal(denominator), unmatched, 0,
                unknownCondition, preliminary, unassignedReturn, unassignedMetricReturn);
    }
}

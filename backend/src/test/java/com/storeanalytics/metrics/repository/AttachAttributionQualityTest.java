package com.storeanalytics.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.model.AttachDenominatorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AttachAttributionQualityTest {

    @Test
    void qualityOnlyProjectionPreservesSellerValuesAndLimitsOnlyAffectedMetric() {
        var seller = new AttachRateAggregate("CASE_SAMSUNG", "CASE_SAMSUNG", AttachDenominatorCode.SAMSUNG,
                BigDecimal.valueOf(2), BigDecimal.valueOf(3), 1, 0, 2, false, 0, 0);
        var quality = new AttachAttributionQuality("CASE_SAMSUNG", 5, 6, 1);
        var result = seller.withPotentialStoreAttributionRisk(quality);

        assertThat(result.numeratorReceiptCount()).isEqualByComparingTo("2");
        assertThat(result.denominatorReceiptCount()).isEqualByComparingTo("3");
        assertThat(result.unmatchedNumeratorItemCount()).isOne();
        assertThat(result.unknownDeviceConditionItemCount()).isEqualTo(2);
        assertThat(result.ambiguousWarrantyItemCount()).isEqualTo(5);
        assertThat(result.unassignedReturnItemCount()).isEqualTo(6);
        assertThat(result.unassignedMetricReturnItemCount()).isOne();
        assertThat(result.preliminary()).isTrue();
        assertThat(seller.withPotentialStoreAttributionRisk(
                new AttachAttributionQuality("CASE_SAMSUNG", 5, 6, 0)).preliminary()).isFalse();
    }

    @Test
    void unknownAuthorPendingRoleLimitsMetricWithoutInventingAConfirmedReturn() {
        var risk = new AttachAttributionQuality("CHARGER_CABLE", 0, 0, 0, true);
        assertThat(risk.preliminary()).isTrue();
        assertThat(risk.unassignedReturnItemCount()).isZero();
        assertThat(risk.unassignedMetricReturnItemCount()).isZero();
    }

    @Test
    void pendingWarrantyCountsArePotentialRiskOnlyForWarrantyCodes() {
        assertThat(new AttachAttributionQuality("WARRANTY_GENERIC_NEW", 1, 0, 0).preliminary()).isTrue();
        assertThat(new AttachAttributionQuality("WARRANTY_GENERIC_USED", 1, 0, 0).preliminary()).isTrue();
        assertThat(new AttachAttributionQuality("PREMIUM_PROTECTION", 1, 0, 0).preliminary()).isFalse();
        assertThat(new AttachAttributionQuality("WARRANTY_GENERIC_NEW", 0, 1, 0).preliminary()).isFalse();
    }
}

package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.SellerPeriodFacts;
import com.storeanalytics.metrics.service.SellerPeriodMetrics;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SellerWeeklyAdditionalSalesProjectorTest {

    private final SellerWeeklyAdditionalSalesProjector projector =
            new SellerWeeklyAdditionalSalesProjector();

    @Test
    void showsWeeklyAdditionalShareAndMixWithDistinctDenominators() {
        var result = projector.project(
                facts("1000", "150", "100", "50"),
                facts("800", "80", "60", "20"), false, true);

        assertThat(result.additionalRevenue().current()).isEqualByComparingTo("150");
        assertThat(result.additionalRevenue().previous()).isEqualByComparingTo("80");
        assertThat(result.additionalShare().current()).isEqualByComparingTo("15.00");
        assertThat(result.accessoryMixShare()).isEqualByComparingTo("66.67");
        assertThat(result.serviceMixShare()).isEqualByComparingTo("33.33");
        assertThat(result.integrityResidual()).isZero();
        assertThat(result.compositionChartSafe()).isTrue();
        assertThat(result.additionalShare().evidenceRefs())
                .containsExactly("SELLERS.ADDITIONAL_SHARE");
    }

    @Test
    void signedReturnsRemainVisibleWithoutMisleadingCompositionChart() {
        var result = projector.project(
                facts("1000", "100", "-20", "120"),
                facts("900", "90", "30", "60"), false, true);

        assertThat(result.accessoryRevenue()).isEqualByComparingTo("-20");
        assertThat(result.accessoryMixShare()).isEqualByComparingTo("-20.00");
        assertThat(result.serviceMixShare()).isEqualByComparingTo("120.00");
        assertThat(result.compositionChartSafe()).isFalse();
    }

    @Test
    void nonPositiveTotalMakesAdditionalShareUnavailableButKeepsAmounts() {
        var result = projector.project(
                facts("0", "100", "40", "60"),
                facts("900", "90", "30", "60"), false, true);

        assertThat(result.additionalShare().metricState()).isEqualTo(MetricState.UNAVAILABLE);
        assertThat(result.additionalShare().current()).isNull();
        assertThat(result.additionalRevenue().current()).isEqualByComparingTo("100");
        assertThat(result.compositionChartSafe()).isFalse();
    }

    @Test
    void missingPreviousDenominatorDoesNotHideProvenCurrentShare() {
        var result = projector.project(
                facts("1000", "150", "100", "50"),
                facts("0", "0", "0", "0"), false, true);

        assertThat(result.additionalShare().metricState()).isEqualTo(MetricState.READY);
        assertThat(result.additionalShare().current()).isEqualByComparingTo("15");
        assertThat(result.additionalShare().previous()).isNull();
        assertThat(result.additionalShare().absoluteDelta()).isNull();
    }

    @Test
    void incompleteClassificationLimitsComparisonsAndPreventsChart() {
        var result = projector.project(
                facts("1000", "150", "100", "50"),
                facts("800", "80", "60", "20"), false, false);

        assertThat(result.additionalRevenue().metricState()).isEqualTo(MetricState.LIMITED);
        assertThat(result.additionalShare().metricState()).isEqualTo(MetricState.LIMITED);
        assertThat(result.compositionChartSafe()).isFalse();
    }

    @Test
    void blockedSourceDoesNotPublishApparentZeros() {
        var result = projector.project(
                facts("0", "0", "0", "0"),
                facts("0", "0", "0", "0"), true, false);

        assertThat(result.additionalRevenue().metricState()).isEqualTo(MetricState.UNAVAILABLE);
        assertThat(result.additionalRevenue().current()).isNull();
        assertThat(result.accessoryRevenue()).isNull();
        assertThat(result.compositionChartSafe()).isFalse();
    }

    @Test
    void blockedSourceDoesNotRequireIncompleteCategoryFacts() {
        var result = projector.project(mock(SellerPeriodFacts.class),
                mock(SellerPeriodFacts.class), true, false);

        assertThat(result.additionalRevenue().metricState()).isEqualTo(MetricState.UNAVAILABLE);
        assertThat(result.additionalShare().current()).isNull();
    }

    @Test
    void inconsistentAdditionalGroupFailsClosed() {
        assertThatThrownBy(() -> projector.project(
                facts("1000", "151", "100", "50"),
                facts("800", "80", "60", "20"), false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    private SellerPeriodFacts facts(
            String revenue, String additional, String accessory, String service
    ) {
        SellerPeriodFacts facts = mock(SellerPeriodFacts.class);
        SellerPeriodMetrics metrics = mock(SellerPeriodMetrics.class);
        CategoryKpiResult categories = mock(CategoryKpiResult.class);
        CategoryKpiMetrics totals = mock(CategoryKpiMetrics.class);
        when(facts.metrics()).thenReturn(metrics);
        when(metrics.totals()).thenReturn(totals);
        when(metrics.categories()).thenReturn(categories);
        when(totals.netRevenue()).thenReturn(new BigDecimal(revenue));
        List<CategoryKpiGroup> groups = List.of(
                group("ADDITIONAL_REVENUE", additional),
                group("ACCESSORY", accessory),
                group("SERVICE", service));
        when(categories.groups()).thenReturn(groups);
        return facts;
    }

    private CategoryKpiGroup group(String code, String revenue) {
        CategoryKpiMetrics metrics = mock(CategoryKpiMetrics.class);
        when(metrics.netRevenue()).thenReturn(new BigDecimal(revenue));
        return new CategoryKpiGroup(code, code, metrics);
    }
}

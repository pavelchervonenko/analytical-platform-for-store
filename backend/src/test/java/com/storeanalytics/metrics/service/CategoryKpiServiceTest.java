package com.storeanalytics.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.CategoryKpiAggregate;
import com.storeanalytics.metrics.repository.CategoryKpiRepository;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.DeviceFamily;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CategoryKpiServiceTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    private StoreRepository storeRepository;
    private CategoryKpiRepository categoryKpiRepository;
    private CategoryKpiService service;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        categoryKpiRepository = mock(CategoryKpiRepository.class);
        service = new CategoryKpiService(storeRepository, categoryKpiRepository);
    }

    @Test
    void calculatesCategoryMetricsAndOverlappingBusinessGroups() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(categoryKpiRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(
                        aggregate(
                                "IPHONE_NEW_ASIS",
                                new CategoryFlags(true, true, false),
                                new AggregateValues("100.00", "1.000", "60.00", 1, 0)
                        ),
                        aggregate(
                                "CHARGER_CABLE",
                                new CategoryFlags(false, false, true),
                                new AggregateValues("20.00", "2.000", "10.00", 1, 0)
                        ),
                        aggregate(
                                "UNMAPPED",
                                new CategoryFlags(false, false, false),
                                new AggregateValues("5.00", "1.000", "3.00", 1, 0)
                        )
                ));

        CategoryKpiResult result = service.calculate(storeId, period());

        assertThat(result.formulaVersion()).isEqualTo("category-kpi-v3");
        assertThat(result.groups())
                .extracting(CategoryKpiGroup::groupCode)
                .containsExactly(
                        "PHONES",
                        "DEVICES",
                        "ACCESSORY",
                        "SERVICE",
                        "ADDITIONAL_REVENUE"
                );
        assertThat(result.categories()).hasSize(3);
        assertThat(group(result, "PHONES").metrics().netRevenue())
                .isEqualByComparingTo("100.00");
        assertThat(group(result, "DEVICES").metrics().netRevenue())
                .isEqualByComparingTo("100.00");
        assertThat(category(result, "IPHONE_NEW_ASIS")
                .metrics().averageGrossProfitPerUnit())
                .isEqualByComparingTo("40.00");
        assertThat(group(result, "DEVICES").metrics().averageGrossProfitPerUnit())
                .isEqualByComparingTo("40.00");
        assertThat(group(result, "ADDITIONAL_REVENUE").metrics().netRevenue())
                .isEqualByComparingTo("20.00");
        assertThat(group(result, "ADDITIONAL_REVENUE").metrics().marginPercent())
                .isEqualByComparingTo("50.00");
        assertThat(group(result, "PHONES").metrics().dataQuality().includedItemCount())
                .isOne();
    }

    @Test
    void makesOnlyAffectedCategoryAndGroupCostMetricsUnknown() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(categoryKpiRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(
                        aggregate(
                                "CHARGER_CABLE",
                                AnalyticsCategoryKind.ACCESSORY,
                                new CategoryFlags(false, false, true),
                                new AggregateValues("20.00", "1.000", "10.00", 1, 0)
                        ),
                        aggregate(
                                "SETUP_SERVICE",
                                AnalyticsCategoryKind.SERVICE,
                                new CategoryFlags(false, false, true),
                                new AggregateValues("30.00", "1.000", "0.00", 1, 1)
                        ),
                        aggregate(
                                "IPHONE_NEW_ASIS",
                                new CategoryFlags(true, true, false),
                                new AggregateValues("100.00", "1.000", "60.00", 1, 0)
                        )
                ));

        CategoryKpiResult result = service.calculate(storeId, period());

        CategoryKpiMetrics setupMetrics = category(result, "SETUP_SERVICE").metrics();
        assertThat(setupMetrics.costAmount()).isNull();
        assertThat(setupMetrics.grossProfit()).isNull();
        assertThat(setupMetrics.marginPercent()).isNull();
        assertThat(setupMetrics.averageGrossProfitPerUnit()).isNull();
        assertThat(setupMetrics.dataQuality().completeCostData()).isFalse();

        CategoryKpiMetrics additional = group(result, "ADDITIONAL_REVENUE").metrics();
        assertThat(additional.netRevenue()).isEqualByComparingTo("50.00");
        assertThat(group(result, "ACCESSORY").metrics().netRevenue())
                .isEqualByComparingTo("20.00");
        assertThat(group(result, "SERVICE").metrics().netRevenue())
                .isEqualByComparingTo("30.00");
        assertThat(additional.costAmount()).isNull();
        assertThat(additional.dataQuality().missingCostItemCount()).isOne();

        assertThat(group(result, "PHONES").metrics().costAmount())
                .isEqualByComparingTo("60.00");
    }

    @Test
    void leavesAverageGrossProfitUnknownForNonPositiveNetQuantity() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(true);
        when(categoryKpiRepository.aggregate(storeId, PERIOD_START, PERIOD_END))
                .thenReturn(List.of(aggregate(
                        "IPHONE_NEW_ASIS",
                        new CategoryFlags(true, true, false),
                        new AggregateValues("10.00", "0.000", "5.00", 2, 0)
                )));

        CategoryKpiMetrics metrics = service.calculate(storeId, period())
                .categories().getFirst().metrics();

        assertThat(metrics.grossProfit()).isEqualByComparingTo("5.00");
        assertThat(metrics.averageGrossProfitPerUnit()).isNull();
    }


    @Test
    void rejectsUnknownStoreBeforeRunningCategoryQuery() {
        UUID storeId = UUID.randomUUID();
        when(storeRepository.existsById(storeId)).thenReturn(false);

        assertThatThrownBy(() -> service.calculate(storeId, period()))
                .isInstanceOf(StoreNotFoundException.class)
                .hasMessageContaining(storeId.toString());
        verifyNoInteractions(categoryKpiRepository);
    }

    private CategoryKpiAggregate aggregate(
            String code,
            CategoryFlags flags,
            AggregateValues values
    ) {
        return aggregate(code, AnalyticsCategoryKind.OTHER, flags, values);
    }

    private CategoryKpiAggregate aggregate(
            String code,
            AnalyticsCategoryKind kind,
            CategoryFlags flags,
            AggregateValues values
    ) {
        return new CategoryKpiAggregate(
                code,
                code,
                kind,
                DeviceFamily.NONE,
                true,
                flags.phone(),
                flags.device(),
                flags.additionalRevenue(),
                new BigDecimal(values.netRevenue()),
                new BigDecimal(values.netQuantity()),
                new BigDecimal(values.costAmount()),
                values.includedItems(),
                values.missingCostItems(),
                0
        );
    }

    private CategoryKpiEntry category(CategoryKpiResult result, String code) {
        return result.categories().stream()
                .filter(entry -> entry.categoryCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private CategoryKpiGroup group(CategoryKpiResult result, String code) {
        return result.groups().stream()
                .filter(group -> group.groupCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private StoreKpiPeriod period() {
        return new StoreKpiPeriod(PERIOD_START, PERIOD_END);
    }

    private record CategoryFlags(
            boolean phone,
            boolean device,
            boolean additionalRevenue
    ) {
    }

    private record AggregateValues(
            String netRevenue,
            String netQuantity,
            String costAmount,
            long includedItems,
            long missingCostItems
    ) {
    }
}

package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SalesStructureBlock;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.StructureNode;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency;
import com.storeanalytics.metrics.service.AttachRateDataQuality;
import com.storeanalytics.metrics.service.AttachRateEntry;
import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.CategoryKpiDataQuality;
import com.storeanalytics.metrics.service.CategoryKpiEntry;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.StoreKpiDataQuality;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.AttachDenominatorCode;
import com.storeanalytics.product.model.DeviceFamily;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewStructureProjectorTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final LocalDate START = LocalDate.of(2026, 8, 17);
    private static final LocalDate END = LocalDate.of(2026, 8, 23);

    private final WeeklyReviewStructureProjector projector =
            new WeeklyReviewStructureProjector(new WeeklyReviewPolicyV1());

    @Test
    void buildsThreeExclusiveTopLevelNodesWithNestedSubtotals() {
        CategoryKpiResult current = categories(
                "600.00", "400.00", "100.00", "200.00",
                List.of(
                        category("PHONE", AnalyticsCategoryKind.DEVICE, true, true, false, "400.00"),
                        category("OTHER_DEVICE", AnalyticsCategoryKind.DEVICE,
                                false, true, false, "200.00"),
                        category("CASE", AnalyticsCategoryKind.ACCESSORY,
                                false, false, true, "100.00"),
                        category("SERVICE", AnalyticsCategoryKind.SERVICE,
                                false, false, true, "200.00")
                )
        );
        CategoryKpiResult previous = categories(
                "500.00", "300.00", "100.00", "200.00", current.categories()
        );

        SalesStructureBlock result = projector.project(
                store("1000.00", 0),
                store("900.00", 0),
                current,
                previous,
                attach("5", "10", 0),
                attach("4", "10", 0)
        );

        assertThat(result.state()).isEqualTo(BlockState.READY);
        assertThat(result.root().children())
                .extracting(StructureNode::code)
                .containsExactly("DEVICES", "ADDITIONAL_REVENUE", "OTHER");
        assertThat(result.root().children())
                .extracting(node -> node.comparison().current())
                .containsExactly(
                        new BigDecimal("600.00"),
                        new BigDecimal("300.00"),
                        new BigDecimal("100.00")
                );
        assertThat(node(result, "DEVICES").children())
                .extracting(StructureNode::code)
                .containsExactly("PHONES", "OTHER_DEVICES");
        assertThat(node(result, "ADDITIONAL_REVENUE").children())
                .extracting(StructureNode::code)
                .containsExactly("ACCESSORY", "SERVICE", "OTHER_ADDITIONAL");
        assertThat(node(result, "DEVICES").childrenIncludedInValue()).isTrue();
    }

    @Test
    void attachComparisonUsesBothWeeksAndRequiresFiveDenominatorReceipts() {
        SalesStructureBlock result = projector.project(
                store("1000.00", 0),
                store("900.00", 0),
                emptyCategories(),
                emptyCategories(),
                attach("6", "5", 0),
                attach("1", "4", 0)
        );

        assertThat(result.attachMetrics()).singleElement().satisfies(metric -> {
            assertThat(metric.comparison().sufficiency()).isEqualTo(Sufficiency.LIMITED);
            assertThat(metric.comparison().materiality())
                    .isEqualTo(Materiality.NOT_EVALUATED);
            assertThat(metric.comparison().currentSample().denominator())
                    .isEqualByComparingTo("5");
            assertThat(metric.comparison().previousSample().denominator())
                    .isEqualByComparingTo("4");
        });
    }

    @Test
    void limitsOnlyStructureWhenClassificationOrHierarchyIsInvalid() {
        CategoryKpiEntry overlap = category(
                "INVALID",
                AnalyticsCategoryKind.DEVICE,
                false,
                true,
                true,
                "10.00"
        );
        CategoryKpiResult invalid = categories(
                "10.00", "0.00", "0.00", "10.00", List.of(overlap)
        );

        SalesStructureBlock result = projector.project(
                store("10.00", 1),
                store("10.00", 0),
                invalid,
                invalid,
                attach("0", "0", 0),
                attach("0", "0", 0)
        );

        assertThat(result.state()).isEqualTo(BlockState.LIMITED);
        assertThat(result.limitations())
                .contains("Часть товарных позиций не классифицирована")
                .anyMatch(message -> message.contains("пересекаются"));
    }

    private StructureNode node(SalesStructureBlock block, String code) {
        return block.root().children().stream()
                .filter(node -> code.equals(node.code()))
                .findFirst()
                .orElseThrow();
    }

    private StoreKpiResult store(String revenue, long unmapped) {
        BigDecimal amount = new BigDecimal(revenue);
        return new StoreKpiResult(
                STORE_ID,
                START,
                END,
                "store-kpi-v3",
                amount,
                BigDecimal.ONE,
                BigDecimal.ZERO.setScale(2),
                amount,
                new BigDecimal("100.00"),
                new StoreKpiDataQuality(true, 1, unmapped, 0, 0, 0, 0)
        );
    }

    private CategoryKpiResult emptyCategories() {
        return categories("0.00", "0.00", "0.00", "0.00", List.of());
    }

    private CategoryKpiResult categories(
            String devices,
            String phones,
            String accessory,
            String service,
            List<CategoryKpiEntry> entries
    ) {
        BigDecimal additional = new BigDecimal(accessory).add(new BigDecimal(service));
        return new CategoryKpiResult(
                STORE_ID,
                START,
                END,
                "category-kpi-v3",
                List.of(
                        group("PHONES", phones),
                        group("DEVICES", devices),
                        group("ACCESSORY", accessory),
                        group("SERVICE", service),
                        group("ADDITIONAL_REVENUE", additional.toPlainString())
                ),
                entries
        );
    }

    private CategoryKpiGroup group(String code, String revenue) {
        return new CategoryKpiGroup(code, code, metrics(revenue));
    }

    private CategoryKpiEntry category(
            String code,
            AnalyticsCategoryKind kind,
            boolean phone,
            boolean device,
            boolean additional,
            String revenue
    ) {
        return new CategoryKpiEntry(
                code,
                code,
                kind,
                DeviceFamily.NONE,
                true,
                phone,
                device,
                additional,
                metrics(revenue)
        );
    }

    private CategoryKpiMetrics metrics(String revenue) {
        BigDecimal amount = new BigDecimal(revenue);
        return new CategoryKpiMetrics(
                amount,
                BigDecimal.ONE,
                BigDecimal.ZERO.setScale(2),
                amount,
                amount,
                new BigDecimal("100.00"),
                new CategoryKpiDataQuality(true, 1, 0, 0)
        );
    }

    private AttachRateResult attach(String numerator, String denominator, long issues) {
        BigDecimal numeratorValue = new BigDecimal(numerator);
        BigDecimal denominatorValue = new BigDecimal(denominator);
        BigDecimal rate = denominatorValue.signum() == 0
                ? null
                : numeratorValue.multiply(BigDecimal.valueOf(100))
                        .divide(denominatorValue, 2, java.math.RoundingMode.HALF_UP);
        return new AttachRateResult(
                STORE_ID,
                START,
                END,
                "attach-rate-v3",
                new AttachRateDataQuality(issues, 0, 0),
                List.of(new AttachRateEntry(
                        "CASE_TO_PHONE",
                        "CASE",
                        AttachDenominatorCode.PHONE,
                        numeratorValue,
                        denominatorValue,
                        rate
                ))
        );
    }
}

package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.DeltaMode.ABSOLUTE;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.DeltaMode.RELATIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.Polarity.CONTEXT;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.Polarity.HIGHER_IS_BETTER;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState.LIMITED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState.READY;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.UNAVAILABLE;

import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.MetricSpec;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AttachMetric;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.SalesStructureBlock;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sample;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.StructureNode;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Unit;
import com.storeanalytics.metrics.service.AttachRateDataQuality;
import com.storeanalytics.metrics.service.AttachRateEntry;
import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.CategoryKpiEntry;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.StoreKpiResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Builds the non-overlapping store structure and metric-scoped attach comparisons. */
public final class WeeklyReviewStructureProjector {

    private static final BigDecimal CATEGORY_THRESHOLD = new BigDecimal("15.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final WeeklyReviewPolicyV1 policy;

    public WeeklyReviewStructureProjector(WeeklyReviewPolicyV1 policy) {
        this.policy = requireNonNull(policy, "policy");
    }

    public SalesStructureBlock project(
            StoreKpiResult currentStore,
            StoreKpiResult previousStore,
            CategoryKpiResult currentCategories,
            CategoryKpiResult previousCategories,
            AttachRateResult currentAttach,
            AttachRateResult previousAttach
    ) {
        StoreKpiResult current = requireNonNull(currentStore, "currentStore");
        StoreKpiResult previous = requireNonNull(previousStore, "previousStore");
        CategoryKpiResult categories = requireNonNull(currentCategories, "currentCategories");
        CategoryKpiResult beforeCategories = requireNonNull(
                previousCategories, "previousCategories"
        );
        AttachRateResult attach = requireNonNull(currentAttach, "currentAttach");
        AttachRateResult beforeAttach = requireNonNull(previousAttach, "previousAttach");

        List<String> limitations = new ArrayList<>();
        boolean classificationComplete = current.dataQuality().unmappedItemCount() == 0
                && previous.dataQuality().unmappedItemCount() == 0;
        if (!classificationComplete) {
            limitations.add("Часть товарных позиций не классифицирована");
        }
        boolean hierarchyValid = hierarchyValid(current, categories)
                && hierarchyValid(previous, beforeCategories);
        if (!hierarchyValid) {
            limitations.add("Группы структуры продаж пересекаются или дают отрицательный остаток");
        }
        boolean attachQualityComplete = attachQualityComplete(attach.dataQuality())
                && attachQualityComplete(beforeAttach.dataQuality());
        if (!attachQualityComplete) {
            limitations.add("Классификация части позиций для расчёта допродаж требует проверки");
        }

        MetricState structureMetricState = classificationComplete && hierarchyValid
                ? MetricState.READY
                : MetricState.LIMITED;
        Sufficiency structureSufficiency = structureMetricState == MetricState.READY
                ? Sufficiency.SUFFICIENT
                : Sufficiency.LIMITED;
        StructureNode root = root(
                current,
                previous,
                categories,
                beforeCategories,
                structureMetricState,
                structureSufficiency
        );
        List<AttachMetric> attachMetrics = attachMetrics(
                attach,
                beforeAttach,
                categories,
                beforeCategories,
                attachQualityComplete && classificationComplete
        );
        BlockState state = limitations.isEmpty() ? READY : LIMITED;
        return new SalesStructureBlock(
                "sales-structure",
                state,
                root,
                attachMetrics,
                limitations
        );
    }

    private StructureNode root(
            StoreKpiResult currentStore,
            StoreKpiResult previousStore,
            CategoryKpiResult current,
            CategoryKpiResult previous,
            MetricState state,
            Sufficiency sufficiency
    ) {
        NodeContext context = new NodeContext(
                currentStore.netRevenue(),
                previousStore.netRevenue(),
                state,
                sufficiency
        );
        BigDecimal currentDevices = groupRevenue(current, "DEVICES");
        BigDecimal previousDevices = groupRevenue(previous, "DEVICES");
        BigDecimal currentAdditional = groupRevenue(current, "ADDITIONAL_REVENUE");
        BigDecimal previousAdditional = groupRevenue(previous, "ADDITIONAL_REVENUE");
        BigDecimal currentOther = currentStore.netRevenue()
                .subtract(currentDevices)
                .subtract(currentAdditional);
        BigDecimal previousOther = previousStore.netRevenue()
                .subtract(previousDevices)
                .subtract(previousAdditional);

        StructureNode devices = node(
                context,
                "DEVICES",
                "Техника",
                currentDevices,
                previousDevices,
                List.of(
                        node(
                                context,
                                "PHONES",
                                "Телефоны",
                                groupRevenue(current, "PHONES"),
                                groupRevenue(previous, "PHONES"),
                                List.of()
                        ),
                        node(
                                context,
                                "OTHER_DEVICES",
                                "Другая техника",
                                currentDevices.subtract(groupRevenue(current, "PHONES")),
                                previousDevices.subtract(groupRevenue(previous, "PHONES")),
                                List.of()
                        )
                )
        );
        BigDecimal currentAccessory = groupRevenue(current, "ACCESSORY");
        BigDecimal previousAccessory = groupRevenue(previous, "ACCESSORY");
        BigDecimal currentService = groupRevenue(current, "SERVICE");
        BigDecimal previousService = groupRevenue(previous, "SERVICE");
        StructureNode additional = node(
                context,
                "ADDITIONAL_REVENUE",
                "Дополнительная выручка",
                currentAdditional,
                previousAdditional,
                List.of(
                        node(
                                context,
                                "ACCESSORY",
                                "Аксессуары",
                                currentAccessory,
                                previousAccessory,
                                List.of()
                        ),
                        node(
                                context,
                                "SERVICE",
                                "Услуги, гарантии и защита",
                                currentService,
                                previousService,
                                List.of()
                        ),
                        node(
                                context,
                                "OTHER_ADDITIONAL",
                                "Прочие дополнительные категории",
                                currentAdditional.subtract(currentAccessory).subtract(currentService),
                                previousAdditional.subtract(previousAccessory).subtract(previousService),
                                List.of()
                        )
                )
        );
        StructureNode other = node(
                context,
                "OTHER",
                "Остальное",
                currentOther,
                previousOther,
                List.of()
        );
        return node(
                context,
                "NET_REVENUE",
                "Чистая выручка",
                currentStore.netRevenue(),
                previousStore.netRevenue(),
                List.of(devices, additional, other)
        );
    }

    private StructureNode node(
            NodeContext context,
            String code,
            String label,
            BigDecimal current,
            BigDecimal previous,
            List<StructureNode> children
    ) {
        String normalizedCode = code.toLowerCase(java.util.Locale.ROOT)
                .replace("_", "-");
        String nodeId = "structure:" + normalizedCode;
        boolean subtotal = !children.isEmpty();
        MetricComparison revenue = policy.compare(
                new MetricSpec(
                        nodeId + ":revenue",
                        code + "_REVENUE",
                        label,
                        Unit.RUB,
                        HIGHER_IS_BETTER,
                        RELATIVE,
                        CATEGORY_THRESHOLD,
                        "STORE.STRUCTURE." + code + ".REVENUE"
                ),
                current,
                previous,
                context.state(),
                context.sufficiency(),
                null,
                null
        );
        BigDecimal currentShare = share(current, context.currentStoreRevenue());
        BigDecimal previousShare = share(previous, context.previousStoreRevenue());
        MetricState shareState = currentShare == null || previousShare == null
                ? UNAVAILABLE
                : context.state();
        Sufficiency shareSufficiency = shareState == UNAVAILABLE
                ? Sufficiency.INSUFFICIENT
                : context.sufficiency();
        MetricComparison share = policy.compare(
                new MetricSpec(
                        nodeId + ":share",
                        code + "_SHARE",
                        "Доля: " + label,
                        Unit.PERCENT,
                        CONTEXT,
                        ABSOLUTE,
                        policy.shareThreshold(),
                        "STORE.STRUCTURE." + code + ".SHARE"
                ),
                currentShare,
                previousShare,
                shareState,
                shareSufficiency,
                null,
                null
        );
        return new StructureNode(
                nodeId,
                code,
                label,
                subtotal,
                subtotal,
                revenue,
                share,
                children
        );
    }

    private List<AttachMetric> attachMetrics(
            AttachRateResult current,
            AttachRateResult previous,
            CategoryKpiResult currentCategories,
            CategoryKpiResult previousCategories,
            boolean qualityComplete
    ) {
        Map<String, AttachRateEntry> currentByCode = byCode(current.rates());
        Map<String, AttachRateEntry> previousByCode = byCode(previous.rates());
        Set<String> codes = new LinkedHashSet<>();
        current.rates().forEach(rate -> codes.add(rate.metricCode()));
        previous.rates().forEach(rate -> codes.add(rate.metricCode()));
        Map<String, String> categoryLabels = categoryLabels(
                currentCategories, previousCategories
        );
        return codes.stream()
                .sorted()
                .map(code -> attachMetric(
                        code,
                        currentByCode.get(code),
                        previousByCode.get(code),
                        categoryLabels,
                        qualityComplete
                ))
                .toList();
    }

    private AttachMetric attachMetric(
            String code,
            AttachRateEntry current,
            AttachRateEntry previous,
            Map<String, String> categoryLabels,
            boolean qualityComplete
    ) {
        BigDecimal currentDenominator = value(
                current, AttachRateEntry::denominatorReceiptCount
        );
        BigDecimal previousDenominator = value(
                previous, AttachRateEntry::denominatorReceiptCount
        );
        Sufficiency sufficiency = weakest(
                policy.attachSufficiency(currentDenominator),
                policy.attachSufficiency(previousDenominator)
        );
        BigDecimal currentRate = value(current, AttachRateEntry::ratePerHundred);
        BigDecimal previousRate = value(previous, AttachRateEntry::ratePerHundred);
        MetricState state = currentRate == null || previousRate == null
                ? UNAVAILABLE
                : qualityComplete ? MetricState.READY : MetricState.LIMITED;
        if (state == MetricState.LIMITED) {
            sufficiency = Sufficiency.LIMITED;
        }
        String categoryCode = current != null
                ? current.numeratorCategoryCode()
                : previous.numeratorCategoryCode();
        String label = categoryLabels.getOrDefault(categoryCode, categoryCode);
        MetricComparison comparison = policy.compare(
                new MetricSpec(
                        "attach:" + code.toLowerCase(java.util.Locale.ROOT),
                        code,
                        label,
                        Unit.PER_100,
                        HIGHER_IS_BETTER,
                        ABSOLUTE,
                        policy.attachThreshold(),
                        "STORE.ATTACH." + code
                ),
                currentRate,
                previousRate,
                state,
                sufficiency,
                sample(current),
                sample(previous)
        );
        return new AttachMetric(
                "attach:" + code.toLowerCase(java.util.Locale.ROOT),
                code,
                label,
                comparison
        );
    }

    private Sample sample(AttachRateEntry rate) {
        if (rate == null) {
            return null;
        }
        return new Sample(
                rate.numeratorReceiptCount(),
                rate.denominatorReceiptCount(),
                "Чеки с дополнительной категорией",
                "Чеки базы"
        );
    }

    private boolean hierarchyValid(StoreKpiResult store, CategoryKpiResult result) {
        BigDecimal devices = groupRevenue(result, "DEVICES");
        BigDecimal phones = groupRevenue(result, "PHONES");
        BigDecimal additional = groupRevenue(result, "ADDITIONAL_REVENUE");
        BigDecimal accessory = groupRevenue(result, "ACCESSORY");
        BigDecimal service = groupRevenue(result, "SERVICE");
        boolean overlappingTopGroups = sumCategories(
                result,
                category -> category.countsAsDevice()
                        && category.countsAsAdditionalRevenue()
        ).signum() != 0;
        BigDecimal other = store.netRevenue().subtract(devices).subtract(additional);
        BigDecimal otherDevices = devices.subtract(phones);
        BigDecimal otherAdditional = additional.subtract(accessory).subtract(service);
        return other.signum() >= 0
                && otherDevices.signum() >= 0
                && otherAdditional.signum() >= 0
                && !overlappingTopGroups;
    }

    private BigDecimal sumCategories(
            CategoryKpiResult result,
            Predicate<CategoryKpiEntry> predicate
    ) {
        return result.categories().stream()
                .filter(predicate)
                .map(category -> category.metrics().netRevenue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal groupRevenue(CategoryKpiResult result, String code) {
        return result.groups().stream()
                .filter(group -> code.equals(group.groupCode()))
                .map(CategoryKpiGroup::metrics)
                .map(metrics -> metrics.netRevenue())
                .findFirst()
                .orElse(BigDecimal.ZERO.setScale(2));
    }

    private Map<String, AttachRateEntry> byCode(List<AttachRateEntry> rates) {
        Map<String, AttachRateEntry> result = new HashMap<>();
        rates.forEach(rate -> result.put(rate.metricCode(), rate));
        return result;
    }

    private Map<String, String> categoryLabels(
            CategoryKpiResult current,
            CategoryKpiResult previous
    ) {
        Map<String, String> result = new HashMap<>();
        previous.categories().forEach(category -> result.put(
                category.categoryCode(), category.categoryName()
        ));
        current.categories().forEach(category -> result.put(
                category.categoryCode(), category.categoryName()
        ));
        return result;
    }

    private boolean attachQualityComplete(AttachRateDataQuality quality) {
        return quality.unmatchedNumeratorItemCount() == 0
                && quality.ambiguousWarrantyItemCount() == 0
                && quality.unknownDeviceConditionItemCount() == 0;
    }

    private BigDecimal share(BigDecimal part, BigDecimal total) {
        if (part == null || total == null || total.signum() <= 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
    }

    private Sufficiency weakest(Sufficiency first, Sufficiency second) {
        if (first == Sufficiency.INSUFFICIENT || second == Sufficiency.INSUFFICIENT) {
            return Sufficiency.INSUFFICIENT;
        }
        if (first == Sufficiency.LIMITED || second == Sufficiency.LIMITED) {
            return Sufficiency.LIMITED;
        }
        return Sufficiency.SUFFICIENT;
    }

    private record NodeContext(
            BigDecimal currentStoreRevenue,
            BigDecimal previousStoreRevenue,
            MetricState state,
            Sufficiency sufficiency
    ) {
    }

    private <T> BigDecimal value(T source, java.util.function.Function<T, BigDecimal> getter) {
        return source == null ? null : getter.apply(source);
    }
}

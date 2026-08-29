package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.DeltaMode.ABSOLUTE;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.DeltaMode.RELATIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.Polarity.CONTEXT;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.Polarity.HIGHER_IS_BETTER;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState.INSUFFICIENT;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState.LIMITED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState.READY;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.GeneratedBy.DETERMINISTIC;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality.MATERIAL;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.UNAVAILABLE;

import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.MetricSpec;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Action;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ActionTarget;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AttachMetric;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.BenchmarkPolicy;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.EmployeeCard;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.EmployeeMetricSet;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Observation;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeerComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.RosterSummary;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sample;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.TeamBlock;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Unit;
import com.storeanalytics.interpretation.snapshot.EmployeeSalesSampleFacts;
import com.storeanalytics.performance.service.EmployeeAttachRatingEntry;
import com.storeanalytics.performance.service.EmployeeRatingEntry;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Projects aggregate team facts and strictly separate employee-owned cards. */
public final class WeeklyReviewTeamEmployeeProjector {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");
    private static final int EMPLOYEE_LIMIT = 100;

    private final WeeklyReviewPolicyV1 policy;

    public WeeklyReviewTeamEmployeeProjector(WeeklyReviewPolicyV1 policy) {
        this.policy = requireNonNull(policy, "policy");
    }

    public Projection project(
            EmployeeRatingResult currentRatings,
            EmployeeRatingResult previousRatings,
            EmployeeSalesSampleFacts currentSales,
            EmployeeSalesSampleFacts previousSales,
            long currentUnattributedReturns,
            long previousUnattributedReturns,
            Map<String, String> attachLabels
    ) {
        EmployeeRatingResult current = requireNonNull(currentRatings, "currentRatings");
        EmployeeRatingResult previous = requireNonNull(previousRatings, "previousRatings");
        EmployeeSalesSampleFacts sales = requireNonNull(currentSales, "currentSales");
        EmployeeSalesSampleFacts beforeSales = requireNonNull(previousSales, "previousSales");
        Map<String, String> labels = Map.copyOf(requireNonNull(attachLabels, "attachLabels"));
        Map<UUID, EmployeeRatingEntry> currentById = byId(current.employees());
        Map<UUID, EmployeeRatingEntry> previousById = byId(previous.employees());
        Set<UUID> employeeIds = new LinkedHashSet<>();
        current.employees().forEach(employee -> employeeIds.add(employee.employeeId()));
        previous.employees().forEach(employee -> employeeIds.add(employee.employeeId()));

        Benchmark benchmark = benchmark(currentById, sales);
        List<EmployeeCard> cards = employeeIds.stream()
                .map(employeeId -> card(
                        currentById.get(employeeId),
                        previousById.get(employeeId),
                        sales.completedSales(employeeId),
                        beforeSales.completedSales(employeeId),
                        benchmark,
                        labels
                ))
                .filter(java.util.Objects::nonNull)
                .sorted(cardOrder())
                .limit(EMPLOYEE_LIMIT)
                .toList();
        TeamBlock team = team(
                cards,
                benchmark,
                currentUnattributedReturns,
                previousUnattributedReturns
        );
        return new Projection(team, cards);
    }

    private EmployeeCard card(
            EmployeeRatingEntry current,
            EmployeeRatingEntry previous,
            long currentSales,
            long previousSales,
            Benchmark benchmark,
            Map<String, String> attachLabels
    ) {
        EmployeeRatingEntry identity = current != null ? current : previous;
        if (identity == null || !identity.employeeActive() || !identity.assignmentActive()) {
            return null;
        }
        boolean hasActivity = currentSales > 0 || previousSales > 0
                || activity(current) || activity(previous);
        if (!hasActivity) {
            return null;
        }
        UUID employeeId = identity.employeeId();
        String publicId = employeeId.toString();
        Sufficiency salesSufficiency = weakest(
                policy.salesSufficiency(currentSales),
                policy.salesSufficiency(previousSales)
        );
        Sufficiency workloadSufficiency = weakest(
                policy.workloadSufficiency(shifts(current), hours(current)),
                policy.workloadSufficiency(shifts(previous), hours(previous))
        );
        EmployeeMetricSet metrics = metrics(
                publicId,
                new EmployeePeriods(current, previous, currentSales, previousSales),
                salesSufficiency,
                workloadSufficiency,
                attachLabels
        );
        List<MetricComparison> candidates = ownCandidates(metrics);
        List<Observation> dynamics = candidates.stream()
                .filter(comparison -> comparison.materiality() == MATERIAL)
                .sorted(Comparator
                        .comparing((MetricComparison comparison) ->
                                comparison.effect() == Effect.NEGATIVE ? 0 : 1)
                        .thenComparing(MetricComparison::code))
                .limit(2)
                .map(comparison -> observation(publicId, comparison))
                .toList();
        Observation attention = dynamics.stream()
                .filter(item -> item.effect() == Effect.NEGATIVE)
                .findFirst()
                .orElse(null);
        Observation strength = dynamics.stream()
                .filter(item -> item.effect() == Effect.POSITIVE)
                .findFirst()
                .orElse(null);
        MetricComparison attentionMetric = attention == null
                ? null
                : candidates.stream()
                        .filter(metric -> attention.evidenceRefs().containsAll(
                                metric.evidenceRefs()
                        ))
                        .findFirst()
                        .orElse(null);
        List<String> limitations = limitations(
                currentSales,
                previousSales,
                salesSufficiency,
                current,
                previous,
                workloadSufficiency
        );
        boolean participatesInBenchmark = benchmark.employeeIds().contains(employeeId);
        PeerComparison peer = participatesInBenchmark && benchmark.allowed()
                ? peerComparison(publicId, revenue(current), benchmark)
                : null;
        String sortGroup = attention != null
                ? "ATTENTION"
                : !limitations.isEmpty() ? "LIMITED" : strength != null ? "POSITIVE" : "STABLE";
        return new EmployeeCard(
                publicId,
                identity.displayName(),
                participatesInBenchmark,
                sortGroup,
                metrics,
                dynamics,
                peer,
                strength,
                attention,
                action(publicId, attentionMetric),
                limitations
        );
    }

    private EmployeeMetricSet metrics(
            String employeePublicId,
            EmployeePeriods periods,
            Sufficiency salesSufficiency,
            Sufficiency workloadSufficiency,
            Map<String, String> attachLabels
    ) {
        EmployeeRatingEntry current = periods.current();
        EmployeeRatingEntry previous = periods.previous();
        long currentSales = periods.currentSales();
        long previousSales = periods.previousSales();
        String evidencePrefix = "EMP:" + employeePublicId + ".";
        MetricComparison completedSales = comparison(new ComparisonInput(
                employeePublicId,
                "COMPLETED_SALES",
                "Количество продаж",
                Unit.COUNT,
                CONTEXT,
                RELATIVE,
                policy.employeeRelativeThreshold(),
                BigDecimal.valueOf(currentSales),
                BigDecimal.valueOf(previousSales),
                MetricState.READY,
                salesSufficiency,
                null,
                null,
                evidencePrefix + "COMPLETED_SALES"
        ));
        MetricComparison netRevenue = comparison(new ComparisonInput(
                employeePublicId,
                "NET_REVENUE",
                "Чистая выручка",
                Unit.RUB,
                HIGHER_IS_BETTER,
                RELATIVE,
                policy.employeeRelativeThreshold(),
                revenue(current),
                revenue(previous),
                MetricState.READY,
                salesSufficiency,
                salesSample(revenue(current), currentSales),
                salesSample(revenue(previous), previousSales),
                evidencePrefix + "NET_REVENUE"
        ));
        MetricComparison additionalRevenue = comparison(new ComparisonInput(
                employeePublicId,
                "ADDITIONAL_REVENUE",
                "Дополнительная выручка",
                Unit.RUB,
                HIGHER_IS_BETTER,
                RELATIVE,
                policy.employeeRelativeThreshold(),
                additional(current),
                additional(previous),
                MetricState.READY,
                salesSufficiency,
                null,
                null,
                evidencePrefix + "ADDITIONAL_REVENUE"
        ));
        BigDecimal currentAdditionalShare = share(additional(current), revenue(current));
        BigDecimal previousAdditionalShare = share(additional(previous), revenue(previous));
        MetricComparison additionalShare = comparison(new ComparisonInput(
                employeePublicId,
                "ADDITIONAL_SHARE",
                "Доля дополнительной выручки",
                Unit.PERCENT,
                HIGHER_IS_BETTER,
                ABSOLUTE,
                policy.shareThreshold(),
                currentAdditionalShare,
                previousAdditionalShare,
                available(currentAdditionalShare, previousAdditionalShare),
                salesSufficiency,
                null,
                null,
                evidencePrefix + "ADDITIONAL_SHARE"
        ));
        MetricComparison shiftCount = comparison(new ComparisonInput(
                employeePublicId,
                "SHIFT_COUNT",
                "Количество смен",
                Unit.COUNT,
                CONTEXT,
                RELATIVE,
                policy.employeeRelativeThreshold(),
                BigDecimal.valueOf(shifts(current)),
                BigDecimal.valueOf(shifts(previous)),
                MetricState.READY,
                workloadSufficiency,
                null,
                null,
                evidencePrefix + "SHIFT_COUNT"
        ));
        MetricComparison workedHours = comparison(new ComparisonInput(
                employeePublicId,
                "WORKED_HOURS",
                "Отработанные часы",
                Unit.HOURS,
                CONTEXT,
                RELATIVE,
                policy.employeeRelativeThreshold(),
                hours(current),
                hours(previous),
                MetricState.READY,
                workloadSufficiency,
                null,
                null,
                evidencePrefix + "WORKED_HOURS"
        ));
        BigDecimal currentPerHour = perHour(current);
        BigDecimal previousPerHour = perHour(previous);
        MetricComparison revenuePerHour = comparison(new ComparisonInput(
                employeePublicId,
                "REVENUE_PER_HOUR",
                "Выручка в час",
                Unit.RUB,
                HIGHER_IS_BETTER,
                RELATIVE,
                policy.employeeRelativeThreshold(),
                currentPerHour,
                previousPerHour,
                available(currentPerHour, previousPerHour),
                workloadSufficiency,
                currentPerHour == null ? null : new Sample(
                        revenue(current), hours(current), "Выручка", "Отработанные часы"
                ),
                previousPerHour == null ? null : new Sample(
                        revenue(previous), hours(previous), "Выручка", "Отработанные часы"
                ),
                evidencePrefix + "REVENUE_PER_HOUR"
        ));
        List<AttachMetric> attach = attachMetrics(
                employeePublicId,
                current,
                previous,
                attachLabels
        );
        return new EmployeeMetricSet(
                completedSales,
                netRevenue,
                additionalRevenue,
                additionalShare,
                shiftCount,
                workedHours,
                revenuePerHour,
                attach
        );
    }

    private MetricComparison comparison(ComparisonInput input) {
        return policy.compare(
                new MetricSpec(
                        "employee:" + input.employeePublicId() + ":"
                                + input.code().toLowerCase(Locale.ROOT),
                        input.code(),
                        input.label(),
                        input.unit(),
                        input.polarity(),
                        input.deltaMode(),
                        input.threshold(),
                        input.evidenceRef()
                ),
                input.current(),
                input.previous(),
                input.state(),
                input.sufficiency(),
                input.currentSample(),
                input.previousSample()
        );
    }

    private List<AttachMetric> attachMetrics(
            String employeePublicId,
            EmployeeRatingEntry current,
            EmployeeRatingEntry previous,
            Map<String, String> labels
    ) {
        Map<String, EmployeeAttachRatingEntry> currentRates = attachByCode(current);
        Map<String, EmployeeAttachRatingEntry> previousRates = attachByCode(previous);
        Set<String> codes = new LinkedHashSet<>();
        codes.addAll(currentRates.keySet());
        codes.addAll(previousRates.keySet());
        return codes.stream()
                .sorted()
                .limit(2)
                .map(code -> employeeAttach(
                        employeePublicId,
                        code,
                        currentRates.get(code),
                        previousRates.get(code),
                        labels.getOrDefault(code, code)
                ))
                .toList();
    }

    private AttachMetric employeeAttach(
            String employeePublicId,
            String code,
            EmployeeAttachRatingEntry current,
            EmployeeAttachRatingEntry previous,
            String label
    ) {
        BigDecimal currentDenominator = attachValue(
                current, EmployeeAttachRatingEntry::denominatorReceiptCount
        );
        BigDecimal previousDenominator = attachValue(
                previous, EmployeeAttachRatingEntry::denominatorReceiptCount
        );
        Sufficiency sufficiency = weakest(
                policy.attachSufficiency(currentDenominator),
                policy.attachSufficiency(previousDenominator)
        );
        BigDecimal currentRate = attachValue(current, EmployeeAttachRatingEntry::ratePercent);
        BigDecimal previousRate = attachValue(previous, EmployeeAttachRatingEntry::ratePercent);
        MetricComparison comparison = comparison(new ComparisonInput(
                employeePublicId,
                code,
                label,
                Unit.PER_100,
                HIGHER_IS_BETTER,
                ABSOLUTE,
                policy.attachThreshold(),
                currentRate,
                previousRate,
                available(currentRate, previousRate),
                sufficiency,
                attachSample(current),
                attachSample(previous),
                "EMP:" + employeePublicId + ".ATTACH." + code
        ));
        return new AttachMetric(
                "employee:" + employeePublicId + ":attach:" + code.toLowerCase(Locale.ROOT),
                code,
                label,
                comparison
        );
    }

    private Sample attachSample(EmployeeAttachRatingEntry rate) {
        return rate == null ? null : new Sample(
                rate.numeratorReceiptCount(),
                rate.denominatorReceiptCount(),
                "Чеки с дополнительной категорией",
                "Чеки базы"
        );
    }

    private List<MetricComparison> ownCandidates(EmployeeMetricSet metrics) {
        List<MetricComparison> result = new ArrayList<>(List.of(
                metrics.netRevenue(),
                metrics.additionalRevenue(),
                metrics.additionalShare(),
                metrics.revenuePerHour()
        ));
        metrics.attachMetrics().forEach(metric -> result.add(metric.comparison()));
        return result;
    }

    private Observation observation(String employeePublicId, MetricComparison comparison) {
        String direction = comparison.effect() == Effect.NEGATIVE ? "снизился" : "вырос";
        String detail = "Текущая неделя: " + format(comparison.current(), comparison.unit())
                + "; предыдущая: " + format(comparison.previous(), comparison.unit());
        return new Observation(
                "employee:" + employeePublicId + ":" + comparison.code().toLowerCase(Locale.ROOT),
                comparison.label() + " " + direction,
                detail,
                comparison.effect(),
                comparison.evidenceRefs()
        );
    }

    private Action action(String employeePublicId, MetricComparison comparison) {
        if (comparison == null || comparison.effect() != Effect.NEGATIVE
                || comparison.previous() == null) {
            return null;
        }
        return new Action(
                "employee:" + employeePublicId + ":restore:"
                        + comparison.code().toLowerCase(Locale.ROOT),
                "HIGH",
                "RESTORE_METRIC",
                "EMPLOYEE",
                employeePublicId,
                "Вернуть показатель «" + comparison.label() + "» к уровню прошлой недели",
                comparison.code(),
                new ActionTarget("AT_LEAST", comparison.previous(), comparison.unit()),
                "Сравнить следующую полную неделю с "
                        + format(comparison.previous(), comparison.unit()),
                "NEXT_FULL_WEEK",
                DETERMINISTIC,
                comparison.evidenceRefs()
        );
    }

    private PeerComparison peerComparison(
            String employeePublicId,
            BigDecimal employeeRevenue,
            Benchmark benchmark
    ) {
        BigDecimal delta = employeeRevenue.subtract(benchmark.median());
        BigDecimal changePercent = benchmark.median().signum() <= 0
                ? null
                : delta.multiply(HUNDRED).divide(
                        benchmark.median().abs(), 2, RoundingMode.HALF_UP
                );
        Effect effect = delta.signum() > 0
                ? Effect.POSITIVE
                : delta.signum() < 0 ? Effect.NEGATIVE : Effect.NEUTRAL;
        return new PeerComparison(
                "NET_REVENUE",
                employeeRevenue,
                benchmark.median(),
                "MEDIAN",
                benchmark.employeeIds().size(),
                delta,
                changePercent,
                effect,
                List.of(
                        "EMP:" + employeePublicId + ".NET_REVENUE",
                        "TEAM.MEDIAN.NET_REVENUE"
                )
        );
    }

    private TeamBlock team(
            List<EmployeeCard> cards,
            Benchmark benchmark,
            long currentUnattributedReturns,
            long previousUnattributedReturns
    ) {
        int active = cards.size();
        int participates = benchmark.employeeIds().size();
        int sufficient = Math.toIntExact(cards.stream()
                .filter(card -> sufficientByAnyMetric(card.metrics()))
                .count());
        int limited = Math.toIntExact(cards.stream()
                .filter(card -> !card.limitations().isEmpty())
                .count());
        int attention = Math.toIntExact(cards.stream()
                .filter(card -> card.attention() != null)
                .count());
        List<Observation> observations = teamObservations(cards);
        boolean attributionLimited = currentUnattributedReturns > 0
                || previousUnattributedReturns > 0;
        BlockState state = cards.isEmpty()
                ? INSUFFICIENT
                : limited > 0 || attributionLimited ? LIMITED : READY;
        List<String> limitations = teamLimitations(
                cards,
                limited,
                currentUnattributedReturns,
                previousUnattributedReturns
        );
        return new TeamBlock(
                "team",
                state,
                new RosterSummary(
                        active,
                        participates,
                        sufficient,
                        limited,
                        active - participates
                ),
                observations,
                attention,
                new BenchmarkPolicy(
                        "MEDIAN",
                        3,
                        benchmark.allowed()
                                ? "Медиана магазина, " + participates + " сотрудников"
                                : "Для медианы магазина нужно минимум 3 сотрудника"
                ),
                limitations
        );
    }

    private List<String> teamLimitations(
            List<EmployeeCard> cards,
            int limitedEmployees,
            long currentUnattributedReturns,
            long previousUnattributedReturns
    ) {
        List<String> result = new ArrayList<>();
        if (cards.isEmpty()) {
            result.add("Нет сотрудников с продажами или сменами за сравниваемые недели");
        } else if (limitedEmployees > 0) {
            result.add("Для части сотрудников недостаточно продаж или смен");
        }
        if (currentUnattributedReturns > 0 || previousUnattributedReturns > 0) {
            result.add("Возвраты без продавца исходной продажи: "
                    + currentUnattributedReturns + " за текущую неделю и "
                    + previousUnattributedReturns + " за предыдущую");
        }
        return List.copyOf(result);
    }

    private List<Observation> teamObservations(List<EmployeeCard> cards) {
        Map<TeamSignal, Long> counts = cards.stream()
                .flatMap(card -> card.ownDynamics().stream())
                .map(observation -> new TeamSignal(
                        metricCode(observation), observation.effect()
                ))
                .collect(Collectors.groupingBy(
                        Function.identity(), LinkedHashMap::new, Collectors.counting()
                ));
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2)
                .sorted(Comparator
                        .comparing((Map.Entry<TeamSignal, Long> entry) ->
                                entry.getKey().effect() == Effect.NEGATIVE ? 0 : 1)
                        .thenComparing(entry -> entry.getKey().metricCode()))
                .limit(2)
                .map(entry -> teamObservation(entry.getKey(), entry.getValue()))
                .toList();
    }

    private Observation teamObservation(TeamSignal signal, long count) {
        String direction = signal.effect() == Effect.NEGATIVE ? "снизился" : "вырос";
        String label = label(signal.metricCode());
        return new Observation(
                "team:" + signal.metricCode().toLowerCase(Locale.ROOT)
                        + ":" + signal.effect().name().toLowerCase(Locale.ROOT),
                label + " " + direction + " у нескольких сотрудников",
                "Изменение выше порога отмечено у " + count + " сотрудников",
                signal.effect(),
                List.of("TEAM." + signal.metricCode() + "." + signal.effect().name())
        );
    }

    private String metricCode(Observation observation) {
        String reference = observation.evidenceRefs().getFirst();
        int separator = reference.lastIndexOf('.');
        return separator < 0 ? reference : reference.substring(separator + 1);
    }

    private String label(String metricCode) {
        return switch (metricCode) {
            case "NET_REVENUE" -> "Чистая выручка";
            case "ADDITIONAL_REVENUE" -> "Дополнительная выручка";
            case "ADDITIONAL_SHARE" -> "Доля дополнительной выручки";
            case "REVENUE_PER_HOUR" -> "Выручка в час";
            default -> metricCode;
        };
    }

    private Benchmark benchmark(
            Map<UUID, EmployeeRatingEntry> current,
            EmployeeSalesSampleFacts sales
    ) {
        List<EmployeeRatingEntry> eligible = current.values().stream()
                .filter(employee -> employee.employeeActive()
                        && employee.assignmentActive()
                        && employee.participatesInRanking())
                .filter(employee -> policy.salesSufficiency(
                        sales.completedSales(employee.employeeId())
                ) == Sufficiency.SUFFICIENT)
                .sorted(Comparator.comparing(EmployeeRatingEntry::employeeId))
                .toList();
        List<BigDecimal> values = eligible.stream()
                .map(EmployeeRatingEntry::netRevenue)
                .sorted()
                .toList();
        BigDecimal median = values.isEmpty() ? null : median(values);
        return new Benchmark(
                eligible.stream().map(EmployeeRatingEntry::employeeId).collect(
                        Collectors.toUnmodifiableSet()
                ),
                median
        );
    }

    private BigDecimal median(List<BigDecimal> values) {
        int middle = values.size() / 2;
        if (values.size() % 2 == 1) {
            return values.get(middle);
        }
        return values.get(middle - 1).add(values.get(middle))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private List<String> limitations(
            long currentSales,
            long previousSales,
            Sufficiency salesSufficiency,
            EmployeeRatingEntry current,
            EmployeeRatingEntry previous,
            Sufficiency workloadSufficiency
    ) {
        List<String> result = new ArrayList<>();
        if (salesSufficiency != Sufficiency.SUFFICIENT) {
            result.add("Недостаточно продаж для сравнения: "
                    + currentSales + " и " + previousSales);
        }
        if (workloadSufficiency != Sufficiency.SUFFICIENT) {
            result.add(shifts(current) == 0 || shifts(previous) == 0
                    ? "Нет смен для расчёта эффективности в одной из недель"
                    : "Недостаточно смен или часов для сравнения эффективности");
        }
        return List.copyOf(result);
    }

    private boolean sufficientByAnyMetric(EmployeeMetricSet metrics) {
        return metrics.netRevenue().sufficiency() == Sufficiency.SUFFICIENT
                || metrics.revenuePerHour().sufficiency() == Sufficiency.SUFFICIENT
                || metrics.attachMetrics().stream().anyMatch(metric ->
                        metric.comparison().sufficiency() == Sufficiency.SUFFICIENT
                );
    }

    private Comparator<EmployeeCard> cardOrder() {
        Map<String, Integer> order = Map.of(
                "ATTENTION", 0,
                "LIMITED", 1,
                "POSITIVE", 2,
                "STABLE", 3
        );
        return Comparator
                .comparingInt((EmployeeCard card) -> order.get(card.sortGroup()))
                .thenComparing(EmployeeCard::displayName);
    }

    private Map<UUID, EmployeeRatingEntry> byId(List<EmployeeRatingEntry> employees) {
        return employees.stream().collect(Collectors.toMap(
                EmployeeRatingEntry::employeeId,
                Function.identity()
        ));
    }

    private Map<String, EmployeeAttachRatingEntry> attachByCode(EmployeeRatingEntry employee) {
        if (employee == null) {
            return Map.of();
        }
        Map<String, EmployeeAttachRatingEntry> result = new HashMap<>();
        employee.attachRates().forEach(rate -> result.put(rate.metricCode(), rate));
        return result;
    }

    private boolean activity(EmployeeRatingEntry employee) {
        return employee != null && (employee.shiftCount() > 0
                || employee.netRevenue().signum() != 0);
    }

    private long shifts(EmployeeRatingEntry employee) {
        return employee == null ? 0 : employee.shiftCount();
    }

    private BigDecimal hours(EmployeeRatingEntry employee) {
        return employee == null ? BigDecimal.ZERO.setScale(2) : employee.workedHours();
    }

    private BigDecimal revenue(EmployeeRatingEntry employee) {
        return employee == null ? BigDecimal.ZERO.setScale(2) : employee.netRevenue();
    }

    private BigDecimal additional(EmployeeRatingEntry employee) {
        return employee == null ? BigDecimal.ZERO.setScale(2) : employee.additionalRevenue();
    }

    private BigDecimal perHour(EmployeeRatingEntry employee) {
        return employee == null ? null : employee.revenuePerHour();
    }

    private BigDecimal share(BigDecimal part, BigDecimal total) {
        return total == null || total.signum() <= 0
                ? null
                : part.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
    }

    private MetricState available(BigDecimal current, BigDecimal previous) {
        return current == null || previous == null ? UNAVAILABLE : MetricState.READY;
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

    private Sample salesSample(BigDecimal revenue, long sales) {
        return new Sample(
                revenue,
                BigDecimal.valueOf(sales),
                "Чистая выручка сотрудника",
                "Завершённые продажи"
        );
    }

    private BigDecimal attachValue(
            EmployeeAttachRatingEntry entry,
            Function<EmployeeAttachRatingEntry, BigDecimal> getter
    ) {
        return entry == null ? null : getter.apply(entry);
    }

    private String format(BigDecimal value, Unit unit) {
        if (value == null) {
            return "нет данных";
        }
        return switch (unit) {
            case RUB -> value.setScale(0, RoundingMode.HALF_UP).toPlainString() + " ₽";
            case PERCENT -> value.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
            case PER_100 -> value.setScale(1, RoundingMode.HALF_UP).toPlainString() + " на 100";
            case HOURS -> value.setScale(1, RoundingMode.HALF_UP).toPlainString() + " ч";
            default -> value.stripTrailingZeros().toPlainString();
        };
    }

    public record Projection(TeamBlock team, List<EmployeeCard> employees) {

        public Projection {
            requireNonNull(team, "team");
            employees = List.copyOf(requireNonNull(employees, "employees"));
        }
    }

    private record EmployeePeriods(
            EmployeeRatingEntry current,
            EmployeeRatingEntry previous,
            long currentSales,
            long previousSales
    ) {
    }

    private record ComparisonInput(
            String employeePublicId,
            String code,
            String label,
            Unit unit,
            WeeklyReviewPolicyV1.Polarity polarity,
            WeeklyReviewPolicyV1.DeltaMode deltaMode,
            BigDecimal threshold,
            BigDecimal current,
            BigDecimal previous,
            MetricState state,
            Sufficiency sufficiency,
            Sample currentSample,
            Sample previousSample,
            String evidenceRef
    ) {
    }

    private record Benchmark(Set<UUID> employeeIds, BigDecimal median) {

        private Benchmark {
            employeeIds = Set.copyOf(requireNonNull(employeeIds, "employeeIds"));
        }

        private boolean allowed() {
            return median != null && employeeIds.size() >= 3;
        }
    }

    private record TeamSignal(String metricCode, Effect effect) {
    }
}

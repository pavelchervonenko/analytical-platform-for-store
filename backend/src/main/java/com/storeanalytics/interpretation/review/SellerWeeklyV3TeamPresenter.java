package com.storeanalytics.interpretation.review;

import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.DeltaMode.ABSOLUTE;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.DeltaMode.RELATIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.Polarity.CONTEXT;
import static com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.Polarity.HIGHER_IS_BETTER;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState.INSUFFICIENT;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect.NEGATIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Effect.POSITIVE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.GeneratedBy.DETERMINISTIC;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Materiality.MATERIAL;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.LIMITED;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.READY;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricState.UNAVAILABLE;
import static com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency.SUFFICIENT;

import com.storeanalytics.interpretation.review.SellerWeeklyTeamFactsProjector.EmployeeContribution;
import com.storeanalytics.interpretation.review.SellerWeeklyTeamFactsProjector.PeriodEmployeeFacts;
import com.storeanalytics.interpretation.review.SellerWeeklyTeamFactsProjector.TeamFinancialFacts;
import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.MetricSpec;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Action;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ActionTarget;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.BenchmarkPolicy;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.EmployeeMetricSet;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.MetricComparison;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Observation;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.RosterSummary;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Sufficiency;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.TeamBlock;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Unit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Locale;
import java.util.UUID;

/** Financial seller cards only; shift-dependent assessment is withheld until completeness is known. */
final class SellerWeeklyV3TeamPresenter {

    private final WeeklyReviewPolicyV1 policy = new WeeklyReviewPolicyV1();

    Projection present(TeamFinancialFacts facts) {
        return present(facts, true, true);
    }

    Projection present(TeamFinancialFacts facts, boolean returnAttributionComplete,
            boolean additionalQualityComplete) {
        List<UUID> active = facts.financiallyActiveIds();
        Map<UUID, WeeklyReviewV3Response.EmployeeCard> allCards = facts.employees().stream()
                .map(item -> card(item, returnAttributionComplete, additionalQualityComplete))
                .collect(Collectors.toMap(item -> UUID.fromString(item.card().employeePublicId()),
                        item -> item));
        List<UUID> order = facts.employees().stream()
                .sorted(Comparator.comparingInt((EmployeeContribution item) ->
                        sortPriority(allCards.get(item.employeeId()).card(), item.hasFinancialActivity()))
                        .thenComparing(EmployeeContribution::employeeId))
                .map(EmployeeContribution::employeeId).toList();
        var window = facts.displayWindow(order);
        List<WeeklyReviewV3Response.EmployeeCard> cards = window.visible().stream()
                .map(item -> allCards.get(item.employeeId())).toList();
        int attention = Math.toIntExact(allCards.values().stream()
                .filter(item -> item.card().attention() != null).count());
        int sufficient = Math.toIntExact(active.stream()
                .filter(id -> allCards.get(id).card().metrics().netRevenue().sufficiency()
                        == SUFFICIENT).count());
        List<String> teamLimitations = new ArrayList<>();
        if (!returnAttributionComplete) {
            teamLimitations.add("Часть возвратов не имеет проверенного автора; личные финансовые действия скрыты.");
        } else if (!additionalQualityComplete) {
            teamLimitations.add("Часть товарных позиций не классифицирована; выводы по допам ограничены.");
        }
        teamLimitations.add("Смены не подтверждены; недоступны показатели нагрузки и сравнение по часам.");
        TeamBlock team = new TeamBlock("team", active.isEmpty() ? INSUFFICIENT
                : returnAttributionComplete && additionalQualityComplete
                        ? com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState.READY
                        : com.storeanalytics.interpretation.review.WeeklyReviewResponse.BlockState.LIMITED,
                new RosterSummary(active.size(), 0, sufficient, active.size() - sufficient, active.size()),
                List.of(), attention,
                new BenchmarkPolicy("MEDIAN", 3, "Сравнение выручки в час недоступно без полноты смен"),
                teamLimitations);
        return new Projection(team, new WeeklyReviewV3Response.TeamDisplay(
                window.totalCount(), window.displayedCount(), window.hiddenCurrentNetRevenue(),
                window.hiddenPreviousNetRevenue(), window.hiddenCurrentAdditionalRevenue(),
                window.hiddenPreviousAdditionalRevenue()), cards);
    }

    Projection unavailable() {
        TeamBlock team = new TeamBlock("team", INSUFFICIENT,
                new RosterSummary(0, 0, 0, 0, 0), List.of(), 0,
                new BenchmarkPolicy("MEDIAN", 3, "Медиана продавцов с достаточной базой"),
                List.of("Финансовые данные продавцов недоступны."));
        return new Projection(team, new WeeklyReviewV3Response.TeamDisplay(0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), List.of());
    }

    private WeeklyReviewV3Response.EmployeeCard card(
            EmployeeContribution employee, boolean returnAttributionComplete,
            boolean additionalQualityComplete) {
        String id = employee.employeeId().toString();
        PeriodEmployeeFacts current = employee.current();
        PeriodEmployeeFacts previous = employee.previous();
        Sufficiency salesSufficiency = policy.salesSufficiency(
                Math.min(current.completedSaleCount(), previous.completedSaleCount()));
        MetricComparison completed = compare(id, "COMPLETED_SALES", "Завершённые продажи",
                Unit.COUNT, BigDecimal.valueOf(current.completedSaleCount()),
                BigDecimal.valueOf(previous.completedSaleCount()),
                new ComparisonQuality(READY, salesSufficiency));
        MetricComparison revenue = compare(id, "NET_REVENUE", "Выручка",
                Unit.RUB, current.netRevenue(), previous.netRevenue(),
                new ComparisonQuality(returnAttributionComplete ? READY : LIMITED, salesSufficiency));
        MetricComparison additional = compare(id, "ADDITIONAL_REVENUE", "Дополнительная выручка",
                Unit.RUB, current.additionalRevenue(), previous.additionalRevenue(),
                new ComparisonQuality(returnAttributionComplete && additionalQualityComplete
                        ? READY : LIMITED, salesSufficiency));
        BigDecimal currentShare = share(current.additionalRevenue(), current.netRevenue());
        BigDecimal previousShare = share(previous.additionalRevenue(), previous.netRevenue());
        MetricComparison additionalShare = compare(id, "ADDITIONAL_SHARE",
                "Доля допов в выручке", Unit.PERCENT, currentShare, previousShare,
                new ComparisonQuality(currentShare == null || previousShare == null ? UNAVAILABLE
                        : returnAttributionComplete && additionalQualityComplete ? READY : LIMITED,
                        salesSufficiency));
        MetricComparison shifts = compare(id, "SHIFT_COUNT", "Смены", Unit.COUNT,
                null, null, new ComparisonQuality(UNAVAILABLE, Sufficiency.INSUFFICIENT));
        MetricComparison hours = compare(id, "WORKED_HOURS", "Отработанные часы", Unit.HOURS,
                null, null, new ComparisonQuality(UNAVAILABLE, Sufficiency.INSUFFICIENT));
        MetricComparison perHour = compare(id, "REVENUE_PER_HOUR", "Выручка в час", Unit.RUB,
                null, null, new ComparisonQuality(UNAVAILABLE, Sufficiency.INSUFFICIENT));
        var metrics = new EmployeeMetricSet(completed, revenue, additional, additionalShare,
                shifts, hours, perHour, List.of());
        List<MetricComparison> dynamics = List.of(revenue, additional, additionalShare).stream()
                .filter(item -> item.materiality() == MATERIAL)
                .sorted(Comparator.comparingInt((MetricComparison item) -> item.effect() == NEGATIVE ? 0 : 1)
                        .thenComparing(MetricComparison::code)).limit(2).toList();
        Observation attention = dynamics.stream().filter(item -> item.effect() == NEGATIVE)
                .findFirst().map(item -> observation(id, item)).orElse(null);
        Observation strength = dynamics.stream().filter(item -> item.effect() == POSITIVE)
                .findFirst().map(item -> observation(id, item)).orElse(null);
        MetricComparison actionMetric = dynamics.stream().filter(item -> item.effect() == NEGATIVE)
                .findFirst().orElse(null);
        List<String> limitations = new ArrayList<>();
        if (!returnAttributionComplete) {
            limitations.add("Неизвестная атрибуция части возвратов ограничивает личный финансовый вывод.");
        } else if (!additionalQualityComplete) {
            limitations.add("Неполная классификация ограничивает личный вывод по допам.");
        }
        if (employee.hasFinancialActivity() && salesSufficiency != SUFFICIENT) {
            limitations.add("Недостаточно завершённых продаж в обеих неделях для личного вывода.");
        }
        var base = new WeeklyReviewResponse.EmployeeCard(id, employee.displayName(), false,
                "FINANCIAL_ONLY", metrics, dynamics.stream().map(item -> observation(id, item)).toList(),
                null, strength, attention, action(id, actionMetric), limitations);
        return new WeeklyReviewV3Response.EmployeeCard(base, true);
    }

    private MetricComparison compare(String id, String code, String label, Unit unit,
            BigDecimal current, BigDecimal previous, ComparisonQuality quality) {
        String key = "EMPLOYEE." + id + "." + code;
        MetricSpec spec = new MetricSpec("employee:" + id + ":" + code.toLowerCase(Locale.ROOT),
                code, label, unit, "COMPLETED_SALES".equals(code) ? CONTEXT : HIGHER_IS_BETTER,
                unit == Unit.PERCENT ? ABSOLUTE : RELATIVE,
                unit == Unit.PERCENT ? policy.shareThreshold() : policy.employeeRelativeThreshold(),
                key);
        return policy.compare(spec, current, previous, quality.state(),
                quality.state() == UNAVAILABLE ? Sufficiency.INSUFFICIENT
                        : quality.state() == LIMITED ? Sufficiency.LIMITED : quality.sufficiency(),
                null, null);
    }

    private int sortPriority(WeeklyReviewResponse.EmployeeCard card, boolean financialActivity) {
        return card.attention() != null ? 0 : financialActivity ? 1 : 2;
    }

    private Observation observation(String id, MetricComparison metric) {
        String direction = metric.effect() == NEGATIVE ? "снизилась" : "выросла";
        return new Observation("employee:" + id + ":" + metric.code().toLowerCase(Locale.ROOT),
                metric.label() + " " + direction,
                "Сравнение с предыдущей завершённой неделей по продавцу рейтинга.",
                metric.effect(), metric.evidenceRefs());
    }

    private Action action(String id, MetricComparison metric) {
        if (metric == null || metric.effect() != NEGATIVE || metric.previous() == null) {
            return null;
        }
        return new Action("employee:" + id + ":restore:" + metric.code().toLowerCase(Locale.ROOT),
                "HIGH", "REVIEW_SELLER_METRIC", "EMPLOYEE", id,
                "Проверить снижение показателя «" + metric.label() + "»",
                metric.code(), new ActionTarget("AT_LEAST", metric.previous(), metric.unit()),
                "Сравнить результат с предыдущей полной неделей после следующей недели.",
                "NEXT_FULL_WEEK", DETERMINISTIC, metric.evidenceRefs());
    }

    private BigDecimal share(BigDecimal part, BigDecimal denominator) {
        return denominator.signum() <= 0 ? null : part.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private record ComparisonQuality(WeeklyReviewResponse.MetricState state,
            Sufficiency sufficiency) {
    }

    record Projection(TeamBlock team, WeeklyReviewV3Response.TeamDisplay display,
            List<WeeklyReviewV3Response.EmployeeCard> cards) {
        Projection {
            cards = List.copyOf(cards);
        }
    }
}

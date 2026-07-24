package com.storeanalytics.performance.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.repository.AttachRateAggregate;
import com.storeanalytics.metrics.repository.AttachRateRepository;
import com.storeanalytics.metrics.repository.StoreKpiAggregate;
import com.storeanalytics.metrics.repository.StoreKpiRepository;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.model.RatingScheme;
import com.storeanalytics.performance.repository.EmployeeAttachRateAggregate;
import com.storeanalytics.performance.repository.EmployeeAttachRateRepository;
import com.storeanalytics.performance.repository.EmployeePerformanceAggregate;
import com.storeanalytics.performance.repository.EmployeePerformanceRepository;
import com.storeanalytics.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeRatingService {

    private static final int MONEY_SCALE = 2;
    private static final int HOURS_SCALE = 2;
    private static final int PERCENT_SCALE = 2;
    private static final int RATE_SCALE = 1;
    private static final int SCORE_SCALE = 2;

    private final StoreRepository storeRepository;
    private final StoreKpiRepository storeKpiRepository;
    private final EmployeePerformanceRepository performanceRepository;
    private final AttachRateRepository storeAttachRateRepository;
    private final EmployeeAttachRateRepository employeeAttachRateRepository;
    private final StorePerformancePlanService planService;
    private final RatingSchemeService schemeService;

    public EmployeeRatingService(
            StoreRepository storeRepository,
            StoreKpiRepository storeKpiRepository,
            EmployeePerformanceRepository performanceRepository,
            AttachRateRepository storeAttachRateRepository,
            EmployeeAttachRateRepository employeeAttachRateRepository,
            StorePerformancePlanService planService,
            RatingSchemeService schemeService
    ) {
        this.storeRepository = storeRepository;
        this.storeKpiRepository = storeKpiRepository;
        this.performanceRepository = performanceRepository;
        this.storeAttachRateRepository = storeAttachRateRepository;
        this.employeeAttachRateRepository = employeeAttachRateRepository;
        this.planService = planService;
        this.schemeService = schemeService;
    }

    @Transactional(readOnly = true)
    public EmployeeRatingResult calculate(UUID storeId, StoreKpiPeriod period) {
        UUID validatedStoreId = requireNonNull(storeId, "storeId");
        StoreKpiPeriod validatedPeriod = requireNonNull(period, "period");
        if (!storeRepository.existsById(validatedStoreId)) {
            throw new StoreNotFoundException(validatedStoreId);
        }

        RatingScheme scheme = schemeService.effectiveOn(validatedPeriod.end());
        StoreKpiAggregate storeKpi = storeKpiRepository.aggregate(
                validatedStoreId, validatedPeriod.start(), validatedPeriod.end()
        );
        BigDecimal storeRevenue = money(storeKpi.netRevenue());
        RatingPlanContext plan = planService.context(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end(),
                storeRevenue
        );
        List<EmployeePerformanceAggregate> aggregates = performanceRepository.aggregate(
                validatedStoreId, validatedPeriod.start(), validatedPeriod.end()
        );
        List<EmployeePerformanceAggregate> candidates = aggregates.stream()
                .filter(this::isCandidate)
                .toList();

        BigDecimal averageRevenue = averageRevenue(candidates);
        BigDecimal averageRevenuePerHour = averageRevenuePerHour(candidates);
        Map<String, AttachRateAggregate> storeAttachRates = storeAttachRateRepository.aggregate(
                validatedStoreId, validatedPeriod.start(), validatedPeriod.end()
        ).stream().collect(HashMap::new, (map, rate) -> map.put(rate.metricCode(), rate), Map::putAll);
        Map<UUID, List<EmployeeAttachRateAggregate>> employeeAttachRates = new HashMap<>();
        employeeAttachRateRepository.aggregate(
                validatedStoreId, validatedPeriod.start(), validatedPeriod.end()
        ).forEach(rate -> employeeAttachRates
                .computeIfAbsent(rate.employeeId(), ignored -> new ArrayList<>())
                .add(rate));

        RatingCalculationContext context = new RatingCalculationContext(
                storeRevenue, averageRevenue, averageRevenuePerHour, plan, scheme, storeAttachRates
        );
        List<EmployeeRatingEntry> entries = aggregates.stream()
                .map(aggregate -> calculateEntry(
                        aggregate,
                        context,
                        employeeAttachRates.getOrDefault(aggregate.employeeId(), List.of())
                ))
                .toList();
        List<EmployeeRatingEntry> ranked = assignDenseRanks(
                entries, scheme.getMinimumCoveragePercent()
        );
        return new EmployeeRatingResult(
                validatedStoreId,
                validatedPeriod.start(),
                validatedPeriod.end(),
                formula(scheme),
                plan,
                ranked,
                EmployeeRatingHistoryView.live()
        );
    }

    private EmployeeRatingEntry calculateEntry(
            EmployeePerformanceAggregate aggregate,
            RatingCalculationContext context,
            List<EmployeeAttachRateAggregate> employeeAttachRates
    ) {
        BigDecimal netRevenue = money(aggregate.netRevenue());
        BigDecimal hours = hours(aggregate.workedHours());
        boolean ratingEligible = aggregate.employeeActive()
                && aggregate.assignmentActive()
                && aggregate.participatesInRanking();
        boolean candidate = ratingEligible && aggregate.shiftCount() > 0;
        BigDecimal revenuePerShift = aggregate.shiftCount() == 0
                ? null
                : netRevenue.divide(
                        BigDecimal.valueOf(aggregate.shiftCount()), MONEY_SCALE, RoundingMode.HALF_UP
                );
        BigDecimal preciseRevenuePerHour = hours.signum() <= 0
                ? null
                : netRevenue.divide(hours, 10, RoundingMode.HALF_UP);
        BigDecimal revenuePerHour = preciseRevenuePerHour == null
                ? null
                : preciseRevenuePerHour.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal accessoryRevenue = money(aggregate.accessoryRevenue());
        BigDecimal serviceRevenue = money(aggregate.serviceRevenue());
        BigDecimal additionalRevenue = money(aggregate.additionalRevenue());
        BigDecimal accessoryShare = share(accessoryRevenue, netRevenue);
        BigDecimal serviceShare = share(serviceRevenue, netRevenue);
        BigDecimal additionalShare = share(additionalRevenue, netRevenue);

        BigDecimal contributionScore = candidate
                ? ratioScore(netRevenue, context.averageRevenue(), context.scheme().getScoreCap()) : null;
        BigDecimal efficiencyScore = candidate && preciseRevenuePerHour != null
                ? ratioScore(
                        preciseRevenuePerHour,
                        context.averageRevenuePerHour(),
                        context.scheme().getScoreCap()
                ) : null;
        BigDecimal structureScore = candidate
                ? structureScore(
                        netRevenue,
                        accessoryShare,
                        serviceShare,
                        context.plan(),
                        context.scheme()
                ) : null;
        List<EmployeeAttachRatingEntry> attachEntries = attachEntries(
                employeeAttachRates, context.storeAttachRates(), context.scheme()
        );
        BigDecimal attachScore = candidate
                ? averageAttachScore(attachEntries) : null;
        RatingScoreBreakdown breakdown = candidate
                ? breakdown(
                        contributionScore,
                        efficiencyScore,
                        structureScore,
                        attachScore,
                        context.scheme()
                ) : emptyBreakdown();

        return new EmployeeRatingEntry(
                aggregate.employeeId(),
                aggregate.displayName(),
                aggregate.employeeActive(),
                aggregate.assignmentActive(),
                aggregate.participatesInRanking(),
                ratingEligible,
                aggregate.shiftCount(),
                hours,
                netRevenue,
                share(netRevenue, context.storeRevenue()),
                revenuePerShift,
                revenuePerHour,
                accessoryRevenue,
                accessoryShare,
                serviceRevenue,
                serviceShare,
                additionalRevenue,
                additionalShare,
                breakdown,
                false,
                null,
                attachEntries
        );
    }

    private List<EmployeeAttachRatingEntry> attachEntries(
            List<EmployeeAttachRateAggregate> employeeRates,
            Map<String, AttachRateAggregate> storeRates,
            RatingScheme scheme
    ) {
        return employeeRates.stream().map(employee -> {
            AttachRateAggregate store = storeRates.get(employee.metricCode());
            BigDecimal numerator = quantity(employee.numeratorQuantity());
            BigDecimal denominator = quantity(employee.denominatorQuantity());
            BigDecimal rate = rate(numerator, denominator);
            BigDecimal storeRate = store == null
                    ? null
                    : rate(
                            quantity(store.numeratorQuantity()),
                            quantity(store.denominatorQuantity())
                    );
            boolean included = denominator.compareTo(scheme.getMinimumAttachDenominator()) >= 0
                    && storeRate != null
                    && storeRate.signum() > 0;
            BigDecimal score = included
                    ? ratioScore(rate, storeRate, scheme.getScoreCap()) : null;
            return new EmployeeAttachRatingEntry(
                    employee.metricCode(),
                    employee.numeratorCategoryCode(),
                    employee.denominatorCode(),
                    numerator,
                    denominator,
                    rate,
                    storeRate,
                    included,
                    score
            );
        }).toList();
    }

    private RatingScoreBreakdown breakdown(
            BigDecimal contributionScore,
            BigDecimal efficiencyScore,
            BigDecimal structureScore,
            BigDecimal attachScore,
            RatingScheme scheme
    ) {
        BigDecimal contributionPoints = weighted(
                contributionScore, scheme.getContributionWeight()
        );
        BigDecimal efficiencyPoints = weighted(efficiencyScore, scheme.getEfficiencyWeight());
        BigDecimal structurePoints = weighted(structureScore, scheme.getStructureWeight());
        BigDecimal attachPoints = weighted(attachScore, scheme.getAttachWeight());
        BigDecimal coverage = BigDecimal.ZERO;
        BigDecimal points = BigDecimal.ZERO;
        if (contributionScore != null) {
            coverage = coverage.add(scheme.getContributionWeight());
            points = points.add(contributionPoints);
        }
        if (efficiencyScore != null) {
            coverage = coverage.add(scheme.getEfficiencyWeight());
            points = points.add(efficiencyPoints);
        }
        if (structureScore != null) {
            coverage = coverage.add(scheme.getStructureWeight());
            points = points.add(structurePoints);
        }
        if (attachScore != null) {
            coverage = coverage.add(scheme.getAttachWeight());
            points = points.add(attachPoints);
        }
        BigDecimal overall = coverage.signum() <= 0
                ? null
                : points.multiply(BigDecimal.valueOf(100))
                        .divide(coverage, SCORE_SCALE, RoundingMode.HALF_UP);
        return new RatingScoreBreakdown(
                contributionScore,
                contributionPoints,
                efficiencyScore,
                efficiencyPoints,
                structureScore,
                structurePoints,
                attachScore,
                attachPoints,
                coverage.setScale(PERCENT_SCALE, RoundingMode.UNNECESSARY),
                overall
        );
    }

    private BigDecimal structureScore(
            BigDecimal netRevenue,
            BigDecimal accessoryShare,
            BigDecimal serviceShare,
            RatingPlanContext plan,
            RatingScheme scheme
    ) {
        if (!plan.complete()) {
            return null;
        }
        if (netRevenue.signum() <= 0) {
            return BigDecimal.ZERO.setScale(SCORE_SCALE);
        }
        BigDecimal accessoryScore = ratioScore(
                accessoryShare, plan.accessoryShareTarget(), scheme.getScoreCap()
        );
        BigDecimal serviceScore = ratioScore(
                serviceShare, plan.serviceShareTarget(), scheme.getScoreCap()
        );
        BigDecimal availableWeight = BigDecimal.ZERO;
        BigDecimal weightedScores = BigDecimal.ZERO;
        if (accessoryScore != null) {
            availableWeight = availableWeight.add(scheme.getAccessoryStructureWeight());
            weightedScores = weightedScores.add(
                    accessoryScore.multiply(scheme.getAccessoryStructureWeight())
            );
        }
        if (serviceScore != null) {
            availableWeight = availableWeight.add(scheme.getServiceStructureWeight());
            weightedScores = weightedScores.add(
                    serviceScore.multiply(scheme.getServiceStructureWeight())
            );
        }
        return availableWeight.signum() <= 0
                ? null
                : weightedScores.divide(availableWeight, SCORE_SCALE, RoundingMode.HALF_UP);
    }

    private List<EmployeeRatingEntry> assignDenseRanks(
            List<EmployeeRatingEntry> entries,
            BigDecimal minimumCoverage
    ) {
        List<EmployeeRatingEntry> scoreOrder = entries.stream()
                .filter(entry -> entry.scores().overallScore() != null)
                .filter(entry -> entry.scores().coveragePercent().compareTo(
                        minimumCoverage
                ) >= 0)
                .sorted(Comparator.comparing(
                        (EmployeeRatingEntry entry) -> entry.scores().overallScore()
                ).reversed().thenComparing(EmployeeRatingEntry::displayName))
                .toList();
        Map<UUID, Integer> ranks = new HashMap<>();
        BigDecimal previousScore = null;
        int denseRank = 0;
        for (EmployeeRatingEntry entry : scoreOrder) {
            BigDecimal score = entry.scores().overallScore();
            if (previousScore == null || score.compareTo(previousScore) != 0) {
                denseRank++;
                previousScore = score;
            }
            ranks.put(entry.employeeId(), denseRank);
        }
        return entries.stream()
                .map(entry -> entry.withRank(ranks.get(entry.employeeId())))
                .sorted(Comparator
                        .comparing(EmployeeRatingEntry::rank, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(EmployeeRatingEntry::displayName))
                .toList();
    }



    private boolean isCandidate(EmployeePerformanceAggregate aggregate) {
        return aggregate.employeeActive()
                && aggregate.assignmentActive()
                && aggregate.participatesInRanking()
                && aggregate.shiftCount() > 0;
    }

    private BigDecimal averageRevenue(List<EmployeePerformanceAggregate> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        BigDecimal total = candidates.stream()
                .map(EmployeePerformanceAggregate::netRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.signum() <= 0
                ? null
                : total.divide(BigDecimal.valueOf(candidates.size()), 10, RoundingMode.HALF_UP);
    }

    private BigDecimal averageRevenuePerHour(List<EmployeePerformanceAggregate> candidates) {
        BigDecimal totalRevenue = candidates.stream()
                .map(EmployeePerformanceAggregate::netRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalHours = candidates.stream()
                .map(EmployeePerformanceAggregate::workedHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return totalRevenue.signum() <= 0 || totalHours.signum() <= 0
                ? null
                : totalRevenue.divide(totalHours, 10, RoundingMode.HALF_UP);
    }

    private BigDecimal averageAttachScore(List<EmployeeAttachRatingEntry> entries) {
        List<BigDecimal> scores = entries.stream()
                .filter(EmployeeAttachRatingEntry::includedInScore)
                .map(EmployeeAttachRatingEntry::score)
                .toList();
        if (scores.isEmpty()) {
            return null;
        }
        return scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(scores.size()), SCORE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal weighted(BigDecimal score, BigDecimal weight) {
        return score == null
                ? null
                : score.multiply(weight)
                        .divide(BigDecimal.valueOf(100), SCORE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal ratioScore(BigDecimal value, BigDecimal baseline, BigDecimal cap) {
        if (value == null || baseline == null || baseline.signum() <= 0) {
            return null;
        }
        BigDecimal score = value.multiply(BigDecimal.valueOf(100))
                .divide(baseline, SCORE_SCALE, RoundingMode.HALF_UP);
        if (score.signum() < 0) {
            return BigDecimal.ZERO.setScale(SCORE_SCALE);
        }
        return score.min(cap).setScale(SCORE_SCALE, RoundingMode.UNNECESSARY);
    }

    private BigDecimal share(BigDecimal part, BigDecimal total) {
        return total == null || total.signum() <= 0
                ? null
                : part.multiply(BigDecimal.valueOf(100))
                        .divide(total, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() <= 0
                ? null
                : numerator.multiply(BigDecimal.valueOf(100))
                        .divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private BigDecimal quantity(BigDecimal value) {
        return value.setScale(3, RoundingMode.UNNECESSARY);
    }

    private BigDecimal hours(BigDecimal value) {
        return value.setScale(HOURS_SCALE, RoundingMode.UNNECESSARY);
    }

    private RatingFormulaView formula(RatingScheme scheme) {
        return new RatingFormulaView(
                scheme.getCode(),
                scheme.getContributionWeight(),
                scheme.getEfficiencyWeight(),
                scheme.getStructureWeight(),
                scheme.getAttachWeight(),
                scheme.getAccessoryStructureWeight(),
                scheme.getServiceStructureWeight(),
                scheme.getMinimumAttachDenominator(),
                scheme.getScoreCap(),
                scheme.getMinimumCoveragePercent()
        );
    }

    private RatingScoreBreakdown emptyBreakdown() {
        return new RatingScoreBreakdown(
                null, null, null, null, null, null, null, null,
                BigDecimal.ZERO.setScale(PERCENT_SCALE), null
        );
    }
    private record RatingCalculationContext(
            BigDecimal storeRevenue,
            BigDecimal averageRevenue,
            BigDecimal averageRevenuePerHour,
            RatingPlanContext plan,
            RatingScheme scheme,
            Map<String, AttachRateAggregate> storeAttachRates
    ) {
    }
}

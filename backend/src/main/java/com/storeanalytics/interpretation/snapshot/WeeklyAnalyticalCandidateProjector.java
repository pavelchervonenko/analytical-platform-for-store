package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateKind.OPPORTUNITY;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateKind.RISK;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality.CONTEXT;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency.INSUFFICIENT;
import static com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency.SUFFICIENT;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateKind;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateSignal;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Comparison;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EmployeeFacts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Unit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Selects analytical claims from backend facts. It does not write narrative: it only establishes
 * which comparisons and team relationships are supported by versioned policy and evidence.
 */
final class WeeklyAnalyticalCandidateProjector {

    private static final List<BenchmarkSpec> TEAM_BENCHMARKS = List.of(
            new BenchmarkSpec(
                    "RATING_CONTRIBUTION_SCORE",
                    "COMMERCIAL_CONTRIBUTION"
            ),
            new BenchmarkSpec(
                    "RATING_EFFICIENCY_SCORE",
                    "TIME_EFFICIENCY"
            ),
            new BenchmarkSpec(
                    "RATING_STRUCTURE_SCORE",
                    "ADDITIONAL_SALES"
            ),
            new BenchmarkSpec(
                    "RATING_ATTACH_SCORE",
                    "ATTACH_RATE"
            )
    );
    private static final List<EmployeeMovementSpec> EMPLOYEE_MOVEMENTS = List.of(
            new EmployeeMovementSpec(
                    "NET_REVENUE",
                    "EMPLOYEE_PERFORMANCE",
                    MovementThreshold.RELATIVE
            ),
            new EmployeeMovementSpec(
                    "REVENUE_PER_HOUR",
                    "TIME_EFFICIENCY",
                    MovementThreshold.RELATIVE
            ),
            new EmployeeMovementSpec(
                    "ADDITIONAL_SHARE_PERCENT",
                    "ADDITIONAL_SALES",
                    MovementThreshold.SHARE
            ),
            new EmployeeMovementSpec(
                    "RATING_OVERALL_SCORE",
                    "EMPLOYEE_PERFORMANCE",
                    MovementThreshold.SCORE
            )
    );

    private final WeeklySnapshotPolicyV3 policy;

    WeeklyAnalyticalCandidateProjector(WeeklySnapshotPolicyV3 policy) {
        this.policy = policy;
    }

    Projection project(List<Fact> storeFacts, List<EmployeeFacts> employeeFacts) {
        Map<String, DraftCandidate> candidates = new TreeMap<>();
        addStoreMovements(candidates, storeFacts);
        addPlanGaps(candidates, storeFacts);
        addCategoryMovements(candidates, storeFacts);
        addAttachMovements(candidates, storeFacts);
        addEmployeeMovements(candidates, employeeFacts);
        addEmployeeAttachMovements(candidates, employeeFacts);

        List<Fact> teamFacts = new ArrayList<>();
        addCoreTeamBenchmarks(candidates, teamFacts, employeeFacts);
        addCategoryTeamBenchmarks(
                candidates,
                teamFacts,
                storeFacts,
                employeeFacts
        );
        addMostImproved(candidates, employeeFacts);
        return new Projection(
                teamFacts.stream()
                        .sorted(Comparator.comparing(Fact::evidenceRef))
                        .toList(),
                finalizeCandidates(candidates)
        );
    }

    private void addStoreMovements(
            Map<String, DraftCandidate> candidates,
            List<Fact> facts
    ) {
        Map<String, String> themes = Map.of(
                "NET_REVENUE", "REVENUE_DYNAMICS",
                "GROSS_PROFIT", "PROFITABILITY"
        );
        themes.forEach((metric, theme) -> facts.stream()
                .filter(fact -> metric.equals(fact.metricCode()))
                .filter(fact -> ("STORE." + metric + ".CURRENT")
                        .equals(fact.evidenceRef()))
                .filter(this::nonNegativeComparison)
                .filter(fact -> policy.materialStoreDelta(relativeDelta(fact)))
                .findFirst()
                .ifPresent(fact -> add(
                        candidates,
                        "STORE." + metric,
                        directionalKind(fact),
                        theme,
                        new CandidateDescriptor(null, null, null, List.of()),
                        fact.sufficiency(),
                        List.of(fact.evidenceRef())
                )));
    }

    private void addPlanGaps(
            Map<String, DraftCandidate> candidates,
            List<Fact> facts
    ) {
        facts.stream()
                .filter(fact -> "PLAN_PROJECTED_COMPLETION_PERCENT"
                        .equals(fact.metricCode()))
                .filter(fact -> fact.sufficiency() != INSUFFICIENT)
                .filter(fact -> policy.materialPlanGap(number(fact)))
                .forEach(fact -> {
                    String direction = identifier(fact.evidenceRef(), "PLAN:");
                    BigDecimal value = number(fact);
                    add(
                            candidates,
                            "STORE.PLAN." + direction,
                            value.compareTo(BigDecimal.valueOf(100)) < 0
                                    ? RISK : OPPORTUNITY,
                            "PLAN",
                            new CandidateDescriptor(null, null, null, List.of()),
                            fact.sufficiency(),
                            planEvidence(facts, fact)
                    );
                });
    }

    private List<String> planEvidence(List<Fact> facts, Fact projected) {
        String prefix = projected.evidenceRef().substring(
                0,
                projected.evidenceRef().lastIndexOf('.') + 1
        );
        List<String> result = new ArrayList<>();
        result.add(projected.evidenceRef());
        facts.stream()
                .filter(fact -> fact.evidenceRef().startsWith(prefix))
                .filter(fact -> "PLAN_ACTUAL_AMOUNT".equals(fact.metricCode())
                        || "PLAN_TARGET_AMOUNT".equals(fact.metricCode()))
                .map(Fact::evidenceRef)
                .sorted()
                .forEach(result::add);
        return result;
    }

    private void addCategoryMovements(
            Map<String, DraftCandidate> candidates,
            List<Fact> facts
    ) {
        List<ScoredFact> material = facts.stream()
                .filter(fact -> "NET_REVENUE".equals(fact.metricCode()))
                .filter(fact -> fact.evidenceRef().startsWith("STORE.CATEGORY:"))
                .filter(this::nonNegativeComparison)
                .map(fact -> categoryMovement(facts, fact))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ScoredFact::magnitude).reversed()
                        .thenComparing(value -> value.fact().evidenceRef()))
                .toList();
        addDirectionalFacts(
                candidates,
                material,
                "STORE.CATEGORY.",
                "CATEGORY_MIX",
                WeeklySnapshotPolicyV3.MAX_CATEGORY_DIRECTIONS
        );
    }

    private ScoredFact categoryMovement(List<Fact> facts, Fact revenue) {
        String prefix = revenue.evidenceRef().substring(
                0,
                revenue.evidenceRef().lastIndexOf("NET_REVENUE.CURRENT")
        );
        Fact share = facts.stream()
                .filter(fact -> (prefix + "REVENUE_SHARE_PERCENT.CURRENT")
                        .equals(fact.evidenceRef()))
                .findFirst()
                .orElse(null);
        if (share == null || !policy.materialCategoryDelta(
                relativeDelta(revenue),
                number(share),
                previous(share)
        )) {
            return null;
        }
        return new ScoredFact(
                revenue,
                absoluteDelta(revenue).abs(),
                List.of(revenue.evidenceRef(), share.evidenceRef())
        );
    }

    private void addDirectionalFacts(
            Map<String, DraftCandidate> candidates,
            List<ScoredFact> facts,
            String keyPrefix,
            String theme,
            int limitPerDirection
    ) {
        for (int direction : List.of(1, -1)) {
            facts.stream()
                    .filter(value -> absoluteDelta(value.fact()).signum() == direction)
                    .limit(limitPerDirection)
                    .forEach(value -> add(
                            candidates,
                            keyPrefix + signalIdentifier(value.fact()) + "."
                                    + directionName(direction),
                            direction > 0 ? OPPORTUNITY : RISK,
                            theme,
                            new CandidateDescriptor(
                                    null,
                                    value.fact().categoryCode(),
                                    null,
                                    List.of()
                            ),
                            value.fact().sufficiency(),
                            value.evidenceRefs()
                    ));
        }
    }

    private void addAttachMovements(
            Map<String, DraftCandidate> candidates,
            List<Fact> facts
    ) {
        List<ScoredFact> material = facts.stream()
                .filter(fact -> "RATE_PER_HUNDRED".equals(fact.metricCode()))
                .filter(fact -> fact.evidenceRef().startsWith("STORE.ATTACH:"))
                .filter(fact -> fact.sufficiency() == SUFFICIENT)
                .map(rate -> attachMovement(facts, rate))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ScoredFact::magnitude).reversed()
                        .thenComparing(value -> value.fact().evidenceRef()))
                .toList();
        addDirectionalFacts(
                candidates,
                material,
                "STORE.ATTACH.",
                "ATTACH_RATE",
                WeeklySnapshotPolicyV3.MAX_CATEGORY_DIRECTIONS
        );
    }

    private ScoredFact attachMovement(List<Fact> facts, Fact rate) {
        String prefix = rate.evidenceRef().substring(
                0,
                rate.evidenceRef().lastIndexOf("RATE_PER_HUNDRED.CURRENT")
        );
        Fact denominator = facts.stream()
                .filter(fact -> (prefix + "DENOMINATOR_QUANTITY.CURRENT")
                        .equals(fact.evidenceRef()))
                .findFirst()
                .orElse(null);
        if (denominator == null
                || !policy.sufficientAttachPair(
                        number(denominator),
                        previous(denominator)
                )
                || !policy.materialAttachGap(absoluteDelta(rate))) {
            return null;
        }
        return new ScoredFact(
                rate,
                absoluteDelta(rate).abs(),
                List.of(rate.evidenceRef(), denominator.evidenceRef())
        );
    }

    private void addEmployeeAttachMovements(
            Map<String, DraftCandidate> candidates,
            List<EmployeeFacts> employees
    ) {
        for (EmployeeFacts employee : employees) {
            if (employee.analysisStatus() == INSUFFICIENT) {
                continue;
            }
            List<ScoredFact> material = employee.facts().stream()
                    .filter(fact -> "RATE_PER_HUNDRED".equals(fact.metricCode()))
                    .filter(fact -> fact.evidenceRef().contains(".ATTACH:"))
                    .filter(fact -> fact.sufficiency() == SUFFICIENT)
                    .map(rate -> attachMovement(employee.facts(), rate))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(ScoredFact::magnitude).reversed()
                            .thenComparing(value -> value.fact().evidenceRef()))
                    .toList();
            for (int direction : List.of(1, -1)) {
                material.stream()
                        .filter(value -> absoluteDelta(value.fact()).signum()
                                == direction)
                        .findFirst()
                        .ifPresent(value -> add(
                                candidates,
                                "EMP." + employee.employeeRef() + ".ATTACH."
                                        + signalIdentifier(value.fact()) + "."
                                        + directionName(direction),
                                direction > 0 ? OPPORTUNITY : RISK,
                                "ATTACH_RATE",
                                new CandidateDescriptor(
                                        employee.employeeRef(),
                                        value.fact().categoryCode(),
                                        null,
                                        List.of()
                                ),
                                policy.mostRestrictive(
                                        employee.analysisStatus(),
                                        value.fact().sufficiency()
                                ),
                                value.evidenceRefs()
                        ));
            }
        }
    }

    private String signalIdentifier(Fact fact) {
        String reference = fact.evidenceRef();
        if (reference.contains(".ATTACH:")) {
            return identifier(reference, "ATTACH:");
        }
        if (fact.categoryCode() == null) {
            throw new IllegalArgumentException(
                    "Analytical signal requires an identifier"
            );
        }
        return fact.categoryCode();
    }

    private void addEmployeeMovements(
            Map<String, DraftCandidate> candidates,
            List<EmployeeFacts> employees
    ) {
        for (EmployeeFacts employee : employees) {
            if (employee.analysisStatus() == INSUFFICIENT) {
                continue;
            }
            List<EmployeeMovement> movements = EMPLOYEE_MOVEMENTS.stream()
                    .map(spec -> movement(employee, spec))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(EmployeeMovement::score).reversed()
                            .thenComparing(value -> value.fact().evidenceRef()))
                    .toList();
            for (int direction : List.of(1, -1)) {
                movements.stream()
                        .filter(value -> absoluteDelta(value.fact()).signum() == direction)
                        .findFirst()
                        .ifPresent(value -> add(
                                candidates,
                                "EMP." + employee.employeeRef() + "."
                                        + value.spec().metricCode() + "."
                                        + directionName(direction),
                                direction > 0 ? OPPORTUNITY : RISK,
                                value.spec().theme(),
                                new CandidateDescriptor(
                                        employee.employeeRef(),
                                        null,
                                        null,
                                        List.of()
                                ),
                                policy.mostRestrictive(
                                        employee.analysisStatus(),
                                        value.fact().sufficiency()
                                ),
                                List.of(value.fact().evidenceRef())
                        ));
            }
        }
    }

    private EmployeeMovement movement(
            EmployeeFacts employee,
            EmployeeMovementSpec spec
    ) {
        Fact fact = employee.facts().stream()
                .filter(value -> spec.metricCode().equals(value.metricCode()))
                .filter(this::isEmployeeCoreFact)
                .filter(value -> value.sufficiency() != INSUFFICIENT)
                .findFirst()
                .orElse(null);
        if (fact == null || fact.comparison() == null
                || absoluteDelta(fact).signum() == 0) {
            return null;
        }
        boolean material = switch (spec.threshold()) {
            case RELATIVE -> nonNegativeComparison(fact)
                    && policy.materialEmployeeRelativeDelta(relativeDelta(fact));
            case SHARE -> policy.materialShareGap(absoluteDelta(fact));
            case SCORE -> policy.materialScoreGap(absoluteDelta(fact));
        };
        if (!material) {
            return null;
        }
        BigDecimal score = switch (spec.threshold()) {
            case RELATIVE -> relativeDelta(fact).abs()
                    .divide(BigDecimal.TEN, 4, RoundingMode.HALF_UP);
            case SHARE -> absoluteDelta(fact).abs()
                    .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            case SCORE -> absoluteDelta(fact).abs()
                    .divide(BigDecimal.valueOf(5), 4, RoundingMode.HALF_UP);
        };
        return new EmployeeMovement(fact, spec, score);
    }

    private boolean isEmployeeCoreFact(Fact fact) {
        String reference = fact.evidenceRef();
        return reference.startsWith("EMP:")
                && !reference.contains(".CATEGORY:")
                && !reference.contains(".GROUP:")
                && !reference.contains(".ATTACH:");
    }

    private void addCoreTeamBenchmarks(
            Map<String, DraftCandidate> candidates,
            List<Fact> teamFacts,
            List<EmployeeFacts> employees
    ) {
        TEAM_BENCHMARKS.forEach(spec -> addBenchmark(
                candidates,
                teamFacts,
                "METRIC:" + spec.metricCode(),
                spec.metricCode(),
                null,
                spec.competencyCode(),
                eligibleValues(
                        employees,
                        fact -> spec.metricCode().equals(fact.metricCode())
                                && isEmployeeCoreFact(fact)
                )
        ));
    }

    private void addCategoryTeamBenchmarks(
            Map<String, DraftCandidate> candidates,
            List<Fact> teamFacts,
            List<Fact> storeFacts,
            List<EmployeeFacts> employees
    ) {
        storeFacts.stream()
                .filter(fact -> "NET_REVENUE".equals(fact.metricCode()))
                .filter(fact -> fact.evidenceRef().startsWith("STORE.CATEGORY:"))
                .filter(fact -> number(fact).signum() >= 0)
                .sorted(Comparator.comparing(this::magnitude).reversed()
                        .thenComparing(Fact::evidenceRef))
                .limit(WeeklySnapshotPolicyV3.MAX_BENCHMARK_CATEGORIES)
                .forEach(storeCategory -> addBenchmark(
                        candidates,
                        teamFacts,
                        "CATEGORY:" + storeCategory.categoryCode()
                                + ".NET_REVENUE",
                        "NET_REVENUE",
                        storeCategory.categoryCode(),
                        "CATEGORY:" + storeCategory.categoryCode(),
                        eligibleValues(
                                employees,
                                fact -> "NET_REVENUE".equals(fact.metricCode())
                                        && storeCategory.categoryCode().equals(
                                        fact.categoryCode()
                                )
                                        && fact.evidenceRef().contains(".CATEGORY:")
                        )
                ));
    }

    private List<EmployeeValue> eligibleValues(
            List<EmployeeFacts> employees,
            Predicate<Fact> predicate
    ) {
        List<EmployeeValue> result = new ArrayList<>();
        for (EmployeeFacts employee : employees) {
            if (employee.analysisStatus() != SUFFICIENT) {
                continue;
            }
            employee.facts().stream()
                    .filter(predicate)
                    .filter(fact -> fact.sufficiency() == SUFFICIENT)
                    .filter(fact -> number(fact).signum() >= 0)
                    .findFirst()
                    .ifPresent(fact -> result.add(new EmployeeValue(
                            employee.employeeRef(),
                            fact,
                            number(fact)
                    )));
        }
        return List.copyOf(result);
    }

    private void addBenchmark(
            Map<String, DraftCandidate> candidates,
            List<Fact> teamFacts,
            String referencePart,
            String metricCode,
            String categoryCode,
            String competencyCode,
            List<EmployeeValue> values
    ) {
        if (!policy.teamBenchmarkAllowed(values.size())) {
            return;
        }
        List<EmployeeValue> ascending = values.stream()
                .sorted(Comparator.comparing(EmployeeValue::value)
                        .thenComparing(EmployeeValue::employeeRef))
                .toList();
        Unit unit = ascending.get(0).fact().unit();
        String prefix = "TEAM." + referencePart + ".";
        BigDecimal q1 = nearestRank(ascending, new BigDecimal("0.25"));
        BigDecimal median = median(ascending);
        BigDecimal q3 = nearestRank(ascending, new BigDecimal("0.75"));
        teamFacts.add(benchmarkFact(
                prefix + "Q1",
                "TEAM_" + metricCode + "_Q1",
                categoryCode,
                unit,
                q1
        ));
        Fact medianFact = benchmarkFact(
                prefix + "MEDIAN",
                "TEAM_" + metricCode + "_MEDIAN",
                categoryCode,
                unit,
                median
        );
        teamFacts.add(medianFact);
        teamFacts.add(benchmarkFact(
                prefix + "Q3",
                "TEAM_" + metricCode + "_Q3",
                categoryCode,
                unit,
                q3
        ));

        List<EmployeeValue> descending = ascending.reversed();
        EmployeeValue best = descending.get(0);
        EmployeeValue next = descending.get(1);
        if (!policy.clearLeader(best.value(), next.value())) {
            return;
        }
        add(
                candidates,
                "TEAM.LEADER." + competencyCode + "." + best.employeeRef(),
                OPPORTUNITY,
                "COMPETENCY_LEADER",
                new CandidateDescriptor(
                        best.employeeRef(),
                        categoryCode,
                        competencyCode,
                        List.of()
                ),
                SUFFICIENT,
                List.of(
                        best.fact().evidenceRef(),
                        next.fact().evidenceRef(),
                        medianFact.evidenceRef()
                )
        );
        List<EmployeeValue> learners = ascending.stream()
                .filter(value -> !value.employeeRef().equals(best.employeeRef()))
                .filter(value -> value.value().compareTo(median) < 0)
                .limit(WeeklySnapshotPolicyV3.MAX_LEARNERS)
                .toList();
        if (learners.isEmpty()) {
            return;
        }
        List<String> evidence = new ArrayList<>();
        evidence.add(best.fact().evidenceRef());
        evidence.add(medianFact.evidenceRef());
        learners.stream().map(EmployeeValue::fact)
                .map(Fact::evidenceRef).forEach(evidence::add);
        add(
                candidates,
                "TEAM.LEARNING." + competencyCode + "." + best.employeeRef(),
                OPPORTUNITY,
                "LEARNING_OPPORTUNITY",
                new CandidateDescriptor(
                        best.employeeRef(),
                        categoryCode,
                        competencyCode,
                        learners.stream().map(EmployeeValue::employeeRef).sorted().toList()
                ),
                SUFFICIENT,
                evidence
        );
    }

    private void addMostImproved(
            Map<String, DraftCandidate> candidates,
            List<EmployeeFacts> employees
    ) {
        List<EmployeeValue> comparable = eligibleValues(
                employees,
                fact -> "RATING_OVERALL_SCORE".equals(fact.metricCode())
                        && isEmployeeCoreFact(fact)
        );
        if (!policy.teamBenchmarkAllowed(comparable.size())) {
            return;
        }
        List<EmployeeValue> improved = comparable.stream()
                .filter(value -> value.fact().comparison() != null)
                .filter(value -> absoluteDelta(value.fact()).signum() > 0)
                .filter(value -> policy.materialScoreGap(
                        absoluteDelta(value.fact())
                ))
                .sorted(Comparator
                        .comparing((EmployeeValue value) ->
                                absoluteDelta(value.fact())).reversed()
                        .thenComparing(EmployeeValue::employeeRef))
                .toList();
        if (improved.isEmpty()) {
            return;
        }
        EmployeeValue best = improved.get(0);
        if (improved.size() > 1 && !policy.clearLeader(
                absoluteDelta(best.fact()),
                absoluteDelta(improved.get(1).fact())
        )) {
            return;
        }
        List<String> evidence = new ArrayList<>();
        evidence.add(best.fact().evidenceRef());
        if (improved.size() > 1) {
            evidence.add(improved.get(1).fact().evidenceRef());
        }
        add(
                candidates,
                "TEAM.MOST_IMPROVED." + best.employeeRef(),
                OPPORTUNITY,
                "MOST_IMPROVED",
                new CandidateDescriptor(best.employeeRef(), null, null, List.of()),
                SUFFICIENT,
                evidence
        );
    }

    private Fact benchmarkFact(
            String evidenceRef,
            String metricCode,
            String categoryCode,
            Unit unit,
            BigDecimal value
    ) {
        return new Fact(
                evidenceRef,
                metricCode,
                categoryCode,
                unit,
                value,
                null,
                SUFFICIENT,
                CONTEXT
        );
    }

    private BigDecimal nearestRank(
            List<EmployeeValue> ascending,
            BigDecimal percentile
    ) {
        int rank = percentile.multiply(BigDecimal.valueOf(ascending.size()))
                .setScale(0, RoundingMode.CEILING)
                .intValue();
        return ascending.get(Math.max(0, rank - 1)).value();
    }

    private BigDecimal median(List<EmployeeValue> ascending) {
        int middle = ascending.size() / 2;
        if (ascending.size() % 2 == 1) {
            return ascending.get(middle).value();
        }
        return ascending.get(middle - 1).value()
                .add(ascending.get(middle).value())
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    private List<CandidateSignal> finalizeCandidates(
            Map<String, DraftCandidate> candidates
    ) {
        List<DraftCandidate> values = List.copyOf(candidates.values());
        List<CandidateSignal> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            DraftCandidate value = values.get(index);
            result.add(new CandidateSignal(
                    "C" + String.format(java.util.Locale.ROOT, "%03d", index + 1),
                    value.kind(),
                    value.theme(),
                    value.employeeRef(),
                    value.categoryCode(),
                    value.competencyCode(),
                    value.targetEmployeeRefs(),
                    value.sufficiency(),
                    value.evidenceRefs()
            ));
        }
        return List.copyOf(result);
    }

    private void add(
            Map<String, DraftCandidate> target,
            String key,
            CandidateKind kind,
            String theme,
            CandidateDescriptor descriptor,
            Sufficiency sufficiency,
            List<String> evidenceRefs
    ) {
        List<String> evidence = List.copyOf(new LinkedHashSet<>(evidenceRefs));
        target.putIfAbsent(key, new DraftCandidate(
                kind,
                theme,
                descriptor.employeeRef(),
                descriptor.categoryCode(),
                descriptor.competencyCode(),
                List.copyOf(descriptor.targetEmployeeRefs()),
                sufficiency,
                evidence
        ));
    }

    private CandidateKind directionalKind(Fact fact) {
        return absoluteDelta(fact).signum() < 0 ? RISK : OPPORTUNITY;
    }

    private String directionName(int direction) {
        return direction > 0 ? "GROWTH" : "DECLINE";
    }

    private boolean nonNegativeComparison(Fact fact) {
        return fact.comparison() != null
                && number(fact).signum() >= 0
                && previous(fact) != null
                && previous(fact).signum() > 0
                && absoluteDelta(fact).signum() != 0;
    }

    private BigDecimal magnitude(Fact fact) {
        BigDecimal current = number(fact).abs();
        BigDecimal before = previous(fact);
        return before == null ? current : current.max(before.abs());
    }

    private BigDecimal number(Fact fact) {
        Object value = fact.value();
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(
                    "Expected numeric fact: " + fact.evidenceRef()
            );
        }
        return new BigDecimal(value.toString());
    }

    private BigDecimal previous(Fact fact) {
        return fact.comparison() == null
                ? null : fact.comparison().previousValue();
    }

    private BigDecimal absoluteDelta(Fact fact) {
        Comparison comparison = fact.comparison();
        return comparison == null || comparison.absoluteDelta() == null
                ? BigDecimal.ZERO : comparison.absoluteDelta();
    }

    private BigDecimal relativeDelta(Fact fact) {
        Comparison comparison = fact.comparison();
        return comparison == null ? null : comparison.relativeDeltaPercent();
    }

    private String identifier(String reference, String marker) {
        int start = reference.indexOf(marker);
        int end = reference.indexOf('.', start + marker.length());
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException(
                    "Malformed analytical evidence reference"
            );
        }
        return reference.substring(start + marker.length(), end);
    }

    record Projection(List<Fact> teamFacts, List<CandidateSignal> candidates) {

        Projection {
            teamFacts = List.copyOf(teamFacts);
            candidates = List.copyOf(candidates);
        }
    }

    private record ScoredFact(
            Fact fact,
            BigDecimal magnitude,
            List<String> evidenceRefs
    ) {
    }

    private record EmployeeMovement(
            Fact fact,
            EmployeeMovementSpec spec,
            BigDecimal score
    ) {
    }

    private record EmployeeMovementSpec(
            String metricCode,
            String theme,
            MovementThreshold threshold
    ) {
    }

    private enum MovementThreshold {
        RELATIVE,
        SHARE,
        SCORE
    }

    private record BenchmarkSpec(String metricCode, String competencyCode) {
    }

    private record EmployeeValue(
            String employeeRef,
            Fact fact,
            BigDecimal value
    ) {
    }

    private record CandidateDescriptor(
            String employeeRef,
            String categoryCode,
            String competencyCode,
            List<String> targetEmployeeRefs
    ) {
    }

    private record DraftCandidate(
            CandidateKind kind,
            String theme,
            String employeeRef,
            String categoryCode,
            String competencyCode,
            List<String> targetEmployeeRefs,
            Sufficiency sufficiency,
            List<String> evidenceRefs
    ) {
    }
}

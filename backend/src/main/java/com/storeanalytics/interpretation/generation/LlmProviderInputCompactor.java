package com.storeanalytics.interpretation.generation;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EmployeeFacts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Creates a bounded provider projection while the immutable snapshot retains every fact.
 * Selection is based only on scope, metric availability and magnitude, never on a
 * pre-written business conclusion.
 */
@Component
public final class LlmProviderInputCompactor {

    private static final int STORE_CATEGORY_LIMIT = 2;
    private static final int STORE_ATTACH_LIMIT = 1;
    private static final int EMPLOYEE_CATEGORY_LIMIT = 1;
    private static final int EMPLOYEE_ATTACH_LIMIT = 1;

    private static final Set<String> STORE_CORE_METRICS = Set.of(
            "NET_REVENUE",
            "GROSS_PROFIT",
            "MARGIN_PERCENT",
            "AVERAGE_RECEIPT",
            "ADDITIONAL_REVENUE_PER_PHONE"
    );
    private static final Set<String> EMPLOYEE_CORE_METRICS = Set.of(
            "COMPLETED_SALES",
            "NET_REVENUE",
            "MARGIN_PERCENT",
            "REVENUE_PER_HOUR",
            "ADDITIONAL_SHARE_PERCENT",
            "RATING_OVERALL_SCORE",
            "RATING_COVERAGE_PERCENT"
    );
    private static final Set<String> INSUFFICIENT_EMPLOYEE_METRICS = Set.of(
            "WORKLOAD_STATUS"
    );
    private static final Set<String> PLAN_METRICS = Set.of(
            "PLAN_ACTUAL_AMOUNT",
            "PLAN_PROJECTED_COMPLETION_PERCENT"
    );
    private static final Set<String> STORE_CATEGORY_METRICS = Set.of(
            "NET_REVENUE",
            "REVENUE_SHARE_PERCENT"
    );
    private static final Set<String> EMPLOYEE_CATEGORY_METRICS = Set.of(
            "NET_REVENUE"
    );
    private static final Set<String> STORE_ATTACH_METRICS = Set.of(
            "NUMERATOR_RECEIPT_COUNT",
            "DENOMINATOR_RECEIPT_COUNT",
            "RATE_PER_HUNDRED"
    );
    private static final Set<String> EMPLOYEE_ATTACH_METRICS = Set.of(
            "NUMERATOR_RECEIPT_COUNT",
            "DENOMINATOR_RECEIPT_COUNT",
            "RATE_PER_HUNDRED"
    );

    public WeeklyInterpretationInput compact(WeeklyInterpretationInput input) {
        Facts compactFacts = new Facts(
                compactStore(input.facts().store()),
                input.facts().team(),
                input.facts().employees().stream()
                        .map(this::compactEmployee)
                        .toList(),
                input.facts().candidateSignals()
        );
        Manifest source = input.manifest();
        Manifest manifest = new Manifest(
                source.employeeRefs(),
                List.of(),
                source.candidateRefs(),
                categoryCodes(compactFacts),
                source.competencyCodes(),
                List.of()
        );
        return new WeeklyInterpretationInput(
                input.contractVersion(),
                input.snapshot(),
                manifest,
                compactFacts
        );
    }

    private List<Fact> compactStore(List<Fact> facts) {
        Set<String> categories = topIdentifiers(
                facts, "CATEGORY:", "NET_REVENUE", STORE_CATEGORY_LIMIT
        );
        Set<String> attach = topIdentifiers(
                facts, "ATTACH:", "DENOMINATOR_RECEIPT_COUNT", STORE_ATTACH_LIMIT
        );
        return facts.stream()
                .filter(fact -> keepStore(fact, categories, attach))
                .toList();
    }

    private boolean keepStore(Fact fact, Set<String> categories, Set<String> attach) {
        String reference = fact.evidenceRef();
        if (reference.contains(".CATEGORY:")) {
            return categories.contains(identifier(reference, "CATEGORY:"))
                    && STORE_CATEGORY_METRICS.contains(fact.metricCode());
        }
        if (reference.contains(".GROUP:")) {
            return false;
        }
        if (reference.contains(".ATTACH:")) {
            return attach.contains(identifier(reference, "ATTACH:"))
                    && STORE_ATTACH_METRICS.contains(fact.metricCode());
        }
        if (reference.contains(".PLAN:")) {
            return PLAN_METRICS.contains(fact.metricCode());
        }
        return STORE_CORE_METRICS.contains(fact.metricCode())
                || fact.materiality() == Materiality.PRIMARY;
    }

    private EmployeeFacts compactEmployee(EmployeeFacts employee) {
        List<Fact> source = employee.facts();
        if (employee.analysisStatus() == Sufficiency.INSUFFICIENT) {
            return copy(employee, source.stream()
                    .filter(fact -> INSUFFICIENT_EMPLOYEE_METRICS.contains(
                            fact.metricCode()
                    ))
                    .toList());
        }
        Set<String> categories = topIdentifiers(
                source, "CATEGORY:", "NET_REVENUE", EMPLOYEE_CATEGORY_LIMIT
        );
        Set<String> attach = topIdentifiers(
                source, "ATTACH:", "DENOMINATOR_RECEIPT_COUNT", EMPLOYEE_ATTACH_LIMIT
        );
        List<Fact> result = source.stream()
                .filter(fact -> keepEmployee(fact, categories, attach))
                .toList();
        return copy(employee, result);
    }

    private boolean keepEmployee(
            Fact fact,
            Set<String> categories,
            Set<String> attach
    ) {
        String reference = fact.evidenceRef();
        if (reference.contains(".CATEGORY:")) {
            return categories.contains(identifier(reference, "CATEGORY:"))
                    && EMPLOYEE_CATEGORY_METRICS.contains(fact.metricCode());
        }
        if (reference.contains(".GROUP:")) {
            return false;
        }
        if (reference.contains(".ATTACH:")) {
            return attach.contains(identifier(reference, "ATTACH:"))
                    && EMPLOYEE_ATTACH_METRICS.contains(fact.metricCode());
        }
        return EMPLOYEE_CORE_METRICS.contains(fact.metricCode());
    }

    private EmployeeFacts copy(EmployeeFacts source, List<Fact> facts) {
        return new EmployeeFacts(
                source.employeeRef(),
                source.analysisStatus(),
                source.availableSections(),
                facts
        );
    }

    private Set<String> topIdentifiers(
            List<Fact> facts,
            String marker,
            String metricCode,
            int limit
    ) {
        List<ScoredIdentifier> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Fact fact : facts) {
            if (!fact.evidenceRef().contains("." + marker)
                    || !metricCode.equals(fact.metricCode())) {
                continue;
            }
            String identifier = identifier(fact.evidenceRef(), marker);
            if (seen.add(identifier)) {
                candidates.add(new ScoredIdentifier(identifier, magnitude(fact)));
            }
        }
        candidates.sort(Comparator
                .comparing(ScoredIdentifier::magnitude).reversed()
                .thenComparing(ScoredIdentifier::identifier));
        return candidates.stream()
                .limit(limit)
                .map(ScoredIdentifier::identifier)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private BigDecimal magnitude(Fact fact) {
        BigDecimal current = number(fact.value()).abs();
        BigDecimal previous = fact.comparison() == null
                || fact.comparison().previousValue() == null
                ? BigDecimal.ZERO
                : fact.comparison().previousValue().abs();
        return current.max(previous);
    }

    private BigDecimal number(Object value) {
        return value instanceof BigDecimal decimal
                ? decimal
                : new BigDecimal(value.toString());
    }

    private String identifier(String reference, String marker) {
        int start = reference.indexOf(marker);
        int end = reference.indexOf('.', start + marker.length());
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("Malformed evidence reference");
        }
        return reference.substring(start + marker.length(), end);
    }

    private List<String> categoryCodes(Facts facts) {
        Set<String> result = new java.util.TreeSet<>();
        facts.store().stream().map(Fact::categoryCode)
                .filter(java.util.Objects::nonNull).forEach(result::add);
        facts.employees().stream().flatMap(employee -> employee.facts().stream())
                .map(Fact::categoryCode)
                .filter(java.util.Objects::nonNull).forEach(result::add);
        return List.copyOf(result);
    }

    private record ScoredIdentifier(String identifier, BigDecimal magnitude) {
    }
}

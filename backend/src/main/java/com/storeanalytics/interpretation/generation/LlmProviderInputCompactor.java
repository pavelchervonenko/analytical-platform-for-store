package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;

import com.storeanalytics.interpretation.contract.WeeklyPrimarySignalPolicy;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EmployeeFacts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Scope;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
            "SHIFT_COUNT",
            "WORKED_HOURS",
            "WORKLOAD_STATUS",
            "REVENUE_PER_HOUR",
            "ADDITIONAL_SHARE_PERCENT",
            "RATING_OVERALL_SCORE",
            "RATING_STRUCTURE_SCORE",
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
            "NUMERATOR_QUANTITY",
            "DENOMINATOR_QUANTITY",
            "RATE_PER_HUNDRED"
    );
    private static final Set<String> EMPLOYEE_ATTACH_METRICS = Set.of(
            "NUMERATOR_QUANTITY",
            "DENOMINATOR_QUANTITY",
            "RATE_PER_HUNDRED"
    );

    public WeeklyInterpretationInput compact(WeeklyInterpretationInput input) {
        return compact(input, false);
    }

    public WeeklyInterpretationInput compact(
            WeeklyInterpretationInput input,
            boolean privacyReduced
    ) {
        return compact(input, privacyReduced, false);
    }

    public WeeklyInterpretationInput compact(
            WeeklyInterpretationInput input,
            boolean privacyReduced,
            boolean boundedStoreSignals
    ) {
        if (privacyReduced) {
            return compactStoreOnly(input, boundedStoreSignals);
        }
        require(!boundedStoreSignals,
                "Bounded store signals require privacy-reduced input");
        Manifest source = input.manifest();
        Set<String> referencedEvidence = referencedEvidence(input);
        Facts compactFacts = new Facts(
                includeReferencedFacts(
                        input.facts().store(),
                        compactStore(input.facts().store()),
                        referencedEvidence
                ),
                input.facts().team(),
                input.facts().employees().stream()
                        .map(employee -> compactEmployee(
                                employee, referencedEvidence
                        ))
                        .toList(),
                input.facts().candidateSignals()
        );
        List<String> categoryCodes = categoryCodes(compactFacts);
        Manifest manifest = new Manifest(
                source.employeeRefs(),
                evidence(source.evidence(), compactFacts, referencedEvidence),
                source.candidateRefs(),
                categoryCodes,
                categoryLabels(source.categoryLabels(), categoryCodes),
                source.competencyCodes(),
                source.limitations()
        );
        return new WeeklyInterpretationInput(
                input.contractVersion(),
                input.snapshot(),
                manifest,
                compactFacts
        );
    }

    private WeeklyInterpretationInput compactStoreOnly(
            WeeklyInterpretationInput input,
            boolean boundedStoreSignals
    ) {
        Manifest source = input.manifest();
        Map<String, Scope> evidenceScopes = source.evidence().stream()
                .collect(java.util.stream.Collectors.toMap(
                        EvidenceIndexEntry::evidenceRef,
                        EvidenceIndexEntry::scope
                ));
        List<WeeklyInterpretationInput.CandidateSignal> candidates =
                input.facts().candidateSignals().stream()
                        .filter(candidate -> candidate.employeeRef() == null)
                        .filter(candidate ->
                                candidate.targetEmployeeRefs().isEmpty())
                        .filter(candidate -> candidate.evidenceRefs().stream()
                                .map(evidenceScopes::get)
                                .noneMatch(scope -> scope == Scope.EMPLOYEE
                                        || scope == Scope.TEAM))
                        .toList();
        if (boundedStoreSignals) {
            candidates = boundedStoreCandidates(input, candidates);
        }
        Set<String> referencedEvidence = new HashSet<>();
        candidates.forEach(candidate ->
                referencedEvidence.addAll(candidate.evidenceRefs()));
        List<Fact> store = includeReferencedFacts(
                input.facts().store(),
                compactStore(input.facts().store()),
                referencedEvidence
        );
        List<Fact> team = input.facts().team().stream()
                .filter(fact -> "RATING_ELIGIBLE_COUNT".equals(
                        fact.metricCode()
                ))
                .toList();
        Facts facts = new Facts(store, team, List.of(), candidates);
        List<String> categoryCodes = categoryCodes(facts);
        Manifest manifest = new Manifest(
                List.of(),
                evidence(source.evidence(), facts, referencedEvidence),
                candidates.stream()
                        .map(WeeklyInterpretationInput.CandidateSignal
                                ::candidateRef)
                        .toList(),
                categoryCodes,
                categoryLabels(source.categoryLabels(), categoryCodes),
                List.of(),
                List.of()
        );
        return new WeeklyInterpretationInput(
                input.contractVersion(),
                input.snapshot(),
                manifest,
                facts
        );
    }

    private List<WeeklyInterpretationInput.CandidateSignal>
            boundedStoreCandidates(
                    WeeklyInterpretationInput input,
                    List<WeeklyInterpretationInput.CandidateSignal> candidates
            ) {
        if (candidates.size() <= 2) {
            return candidates;
        }
        Set<String> allowed = candidates.stream()
                .map(WeeklyInterpretationInput.CandidateSignal::candidateRef)
                .collect(java.util.stream.Collectors.toSet());
        List<WeeklyInterpretationInput.CandidateSignal> ordered =
                WeeklyPrimarySignalPolicy.orderedStoreCandidates(input).stream()
                        .filter(candidate -> allowed.contains(
                                candidate.candidateRef()
                        ))
                        .toList();
        require(!ordered.isEmpty(),
                "Bounded provider projection requires a store candidate");
        WeeklyInterpretationInput.CandidateSignal primary = ordered.get(0);
        WeeklyInterpretationInput.CandidateSignal secondary = ordered.stream()
                .skip(1)
                .filter(candidate -> !candidate.theme().equals(
                        primary.theme()
                ))
                .findFirst()
                .orElse(ordered.get(1));
        return List.of(primary, secondary);
    }

    private List<Fact> compactStore(List<Fact> facts) {
        Set<String> categories = topIdentifiers(
                facts, "CATEGORY:", "NET_REVENUE", STORE_CATEGORY_LIMIT
        );
        Set<String> attach = topIdentifiers(
                facts, "ATTACH:", "DENOMINATOR_QUANTITY", STORE_ATTACH_LIMIT
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

    private EmployeeFacts compactEmployee(
            EmployeeFacts employee,
            Set<String> referencedEvidence
    ) {
        List<Fact> source = employee.facts();
        if (employee.analysisStatus() == Sufficiency.INSUFFICIENT) {
            List<Fact> selected = source.stream()
                    .filter(fact -> INSUFFICIENT_EMPLOYEE_METRICS.contains(
                            fact.metricCode()
                    ))
                    .toList();
            return copy(employee, includeReferencedFacts(
                    source, selected, referencedEvidence
            ));
        }
        Set<String> categories = topIdentifiers(
                source, "CATEGORY:", "NET_REVENUE", EMPLOYEE_CATEGORY_LIMIT
        );
        Set<String> attach = topIdentifiers(
                source, "ATTACH:", "DENOMINATOR_QUANTITY", EMPLOYEE_ATTACH_LIMIT
        );
        List<Fact> result = source.stream()
                .filter(fact -> keepEmployee(fact, categories, attach))
                .toList();
        return copy(employee, includeReferencedFacts(
                source, result, referencedEvidence
        ));
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

    private Set<String> referencedEvidence(WeeklyInterpretationInput input) {
        Set<String> result = new HashSet<>();
        input.manifest().limitations().forEach(
                limitation -> result.addAll(limitation.evidenceRefs())
        );
        input.facts().candidateSignals().forEach(
                candidate -> result.addAll(candidate.evidenceRefs())
        );
        return Set.copyOf(result);
    }

    private List<Fact> includeReferencedFacts(
            List<Fact> source,
            List<Fact> compact,
            Set<String> referencedEvidence
    ) {
        Set<String> retained = new HashSet<>(referencedEvidence);
        compact.stream().map(Fact::evidenceRef).forEach(retained::add);
        return source.stream()
                .filter(fact -> retained.contains(fact.evidenceRef()))
                .toList();
    }

    private List<EvidenceIndexEntry> evidence(
            List<EvidenceIndexEntry> source,
            Facts facts,
            Set<String> referencedEvidence
    ) {
        Set<String> factReferences = new HashSet<>();
        facts.store().stream().map(Fact::evidenceRef).forEach(factReferences::add);
        facts.team().stream().map(Fact::evidenceRef).forEach(factReferences::add);
        facts.employees().stream()
                .flatMap(employee -> employee.facts().stream())
                .map(Fact::evidenceRef)
                .forEach(factReferences::add);
        Set<String> retained = new HashSet<>(referencedEvidence);
        retained.addAll(factReferences);
        List<EvidenceIndexEntry> result = source.stream()
                .filter(entry -> retained.contains(entry.evidenceRef()))
                .toList();
        Set<String> indexed = new HashSet<>();
        Set<String> available = new HashSet<>();
        result.forEach(entry -> {
            indexed.add(entry.evidenceRef());
            if (entry.available()) {
                available.add(entry.evidenceRef());
            }
        });
        require(available.containsAll(factReferences),
                "Compact provider facts require available evidence entries");
        require(indexed.containsAll(referencedEvidence),
                "Provider metadata requires indexed evidence entries");
        return result;
    }

    private Map<String, String> categoryLabels(
            Map<String, String> source,
            List<String> categoryCodes
    ) {
        Map<String, String> result = new java.util.TreeMap<>();
        categoryCodes.forEach(code -> {
            String label = source.get(code);
            if (label != null) {
                result.put(code, label);
            }
        });
        return Map.copyOf(result);
    }

    private record ScoredIdentifier(String identifier, BigDecimal magnitude) {
    }
}

package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EmployeeFacts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Fact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Facts;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Limitation;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.LimitationImpact;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Manifest;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Materiality;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Scope;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Sufficiency;
import com.storeanalytics.metrics.service.EmployeeKpiEntry;
import com.storeanalytics.performance.service.EmployeeRatingEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public final class WeeklySnapshotDraftBuilder {

    private static final int MAX_STORE_FACTS = 200;
    private static final int MAX_TEAM_FACTS = 150;
    private static final int MAX_EMPLOYEE_FACTS = 150;
    private static final int MAX_EVIDENCE = 2000;
    private static final Set<String> COMMERCIAL_METRICS = Set.of(
            "NET_REVENUE",
            "GROSS_PROFIT",
            "MARGIN_PERCENT",
            "STORE_REVENUE_SHARE_PERCENT",
            "RATING_CONTRIBUTION_SCORE"
    );
    private static final Set<String> EFFICIENCY_METRICS = Set.of(
            "REVENUE_PER_SHIFT",
            "REVENUE_PER_HOUR",
            "RATING_EFFICIENCY_SCORE"
    );
    private static final Set<String> ADDITIONAL_SALES_METRICS = Set.of(
            "ADDITIONAL_REVENUE",
            "ADDITIONAL_SHARE_PERCENT",
            "ADDITIONAL_REVENUE_PER_PHONE"
    );
    private static final Set<String> SERVICE_CATEGORY_CODES = Set.of(
            "PREMIUM_PROTECTION",
            "SETUP_SERVICE",
            "WARRANTY_GENERIC"
    );

    private final WeeklySnapshotPolicyV2 policy = new WeeklySnapshotPolicyV2();
    private final SnapshotEmployeePseudonymizer pseudonymizer =
            new SnapshotEmployeePseudonymizer();
    private final StoreSnapshotFactProjector storeProjector =
            new StoreSnapshotFactProjector(policy);
    private final EmployeeSnapshotFactProjector employeeProjector =
            new EmployeeSnapshotFactProjector(policy);
    private final WeeklySnapshotPayloadCodec codec;

    public WeeklySnapshotDraftBuilder(WeeklySnapshotPayloadCodec codec) {
        this.codec = codec;
    }

    public WeeklySnapshotDraft build(WeeklyAnalyticsFacts facts, String timezone) {
        WeeklyAnalyticsFacts source = requireNonNull(facts, "facts");
        String validatedTimezone = requireText(timezone, "timezone");
        SnapshotQualityDecision quality = policy.quality(
                source.sourceDataStatus(),
                source.current().store().dataQuality(),
                source.current().attachRates().dataQuality(),
                source.query().period().end()
        );
        List<SnapshotEmployeeMembership> memberships = memberships(source);
        List<Fact> storeFacts = storeProjector.project(source);
        List<EmployeeFacts> employeeFacts = employeeProjector.project(source, memberships);
        List<Fact> teamFacts = teamFacts(employeeFacts);
        enforceLimits(storeFacts, teamFacts, employeeFacts);

        List<EvidenceIndexEntry> evidence = evidence(
                storeFacts,
                teamFacts,
                employeeFacts,
                quality.unavailableEvidence()
        );
        require(evidence.size() <= MAX_EVIDENCE,
                "Weekly interpretation evidence exceeds schema limit");
        Manifest manifest = new Manifest(
                memberships.stream().map(SnapshotEmployeeMembership::employeeRef).toList(),
                evidence,
                List.of(),
                categoryCodes(storeFacts, employeeFacts),
                competencyCodes(storeFacts, employeeFacts),
                limitations(quality.limitations(), employeeFacts)
        );
        Facts projectedFacts = new Facts(
                storeFacts,
                teamFacts,
                employeeFacts,
                List.of()
        );
        WeeklySnapshotPayload payload = new WeeklySnapshotPayload(1, manifest, projectedFacts);
        return new WeeklySnapshotDraft(
                source.storeId(),
                source.query(),
                validatedTimezone,
                quality.status(),
                WeeklySnapshotPolicyV2.VERSIONS,
                memberships,
                payload,
                codec.hash(payload, memberships)
        );
    }

    private List<SnapshotEmployeeMembership> memberships(WeeklyAnalyticsFacts source) {
        Map<java.util.UUID, EmployeeRatingEntry> currentRatings = ratings(
                source.current()
        );
        Map<java.util.UUID, EmployeeRatingEntry> previousRatings = ratings(
                source.previous()
        );
        List<SnapshotEmployeePseudonymizer.EmployeeIdentity> identities = new ArrayList<>();
        source.current().employees().employees().stream()
                .filter(employee -> relevant(employee, currentRatings.get(employee.employeeId())))
                .map(this::identity)
                .forEach(identities::add);
        source.previous().employees().employees().stream()
                .filter(employee -> relevant(employee, previousRatings.get(employee.employeeId())))
                .map(this::identity)
                .forEach(identities::add);
        return pseudonymizer.assign(identities);
    }

    private Map<java.util.UUID, EmployeeRatingEntry> ratings(WeeklyPeriodFacts period) {
        return period.employeeRatings().employees().stream().collect(
                java.util.stream.Collectors.toMap(
                        EmployeeRatingEntry::employeeId,
                        java.util.function.Function.identity()
                )
        );
    }

    private boolean relevant(EmployeeKpiEntry employee, EmployeeRatingEntry rating) {
        if (employee.unassigned() || !employee.rankingEligible()) {
            return false;
        }
        return employee.dataQuality().includedItemCount() > 0
                || rating != null
                && rating.ratingEligible()
                && rating.shiftCount() > 0;
    }

    private SnapshotEmployeePseudonymizer.EmployeeIdentity identity(EmployeeKpiEntry employee) {
        return new SnapshotEmployeePseudonymizer.EmployeeIdentity(
                employee.employeeId(),
                employee.displayName()
        );
    }

    private List<Fact> teamFacts(List<EmployeeFacts> employeeFacts) {
        long eligibleCount = employeeFacts.stream()
                .filter(employee -> employee.analysisStatus() == Sufficiency.SUFFICIENT)
                .count();
        Sufficiency sufficiency = policy.teamBenchmarkAllowed(eligibleCount)
                ? Sufficiency.SUFFICIENT
                : Sufficiency.INSUFFICIENT;
        return List.of(SnapshotFactFactory.count(
                "TEAM.RATING.ELIGIBLE_COUNT",
                "RATING_ELIGIBLE_COUNT",
                eligibleCount,
                null,
                sufficiency,
                Materiality.CONTEXT
        ));
    }

    private List<Limitation> limitations(
            List<Limitation> storeLimitations,
            List<EmployeeFacts> employeeFacts
    ) {
        List<Limitation> result = new ArrayList<>(storeLimitations);
        employeeFacts.stream()
                .filter(employee -> employee.analysisStatus() != Sufficiency.SUFFICIENT)
                .map(this::employeeLimitation)
                .forEach(result::add);
        return List.copyOf(result);
    }

    private Limitation employeeLimitation(EmployeeFacts employee) {
        boolean unavailable = employee.analysisStatus() == Sufficiency.INSUFFICIENT;
        List<String> evidenceRefs = employee.facts().stream()
                .filter(fact -> "WORKLOAD_STATUS".equals(fact.metricCode())
                        || "RATING_COVERAGE_PERCENT".equals(fact.metricCode()))
                .map(Fact::evidenceRef)
                .distinct()
                .limit(2)
                .toList();
        require(!evidenceRefs.isEmpty(),
                "Employee analysis limitation requires quality evidence");
        return new Limitation(
                unavailable
                        ? "EMPLOYEE_ANALYSIS_INSUFFICIENT"
                        : "EMPLOYEE_ANALYSIS_LIMITED",
                Scope.EMPLOYEE,
                employee.employeeRef(),
                null,
                unavailable
                        ? LimitationImpact.UNAVAILABLE
                        : LimitationImpact.REDUCED_CONFIDENCE,
                List.of(
                        "RESULT",
                        "DYNAMICS",
                        "CATEGORY_PERFORMANCE",
                        "ADDITIONAL_SALES",
                        "TIME_EFFICIENCY",
                        "RATING",
                        "TEAM_COMPARISON",
                        "RECOMMENDATIONS"
                ),
                evidenceRefs
        );
    }

    private List<EvidenceIndexEntry> evidence(
            List<Fact> storeFacts,
            List<Fact> teamFacts,
            List<EmployeeFacts> employeeFacts,
            List<EvidenceIndexEntry> unavailable
    ) {
        Map<String, EvidenceIndexEntry> result = new LinkedHashMap<>();
        List<Fact> allFacts = new ArrayList<>(storeFacts);
        allFacts.addAll(teamFacts);
        employeeFacts.forEach(employee -> allFacts.addAll(employee.facts()));
        allFacts.stream()
                .sorted(Comparator.comparing(Fact::evidenceRef))
                .map(this::availableEvidence)
                .forEach(entry -> putUnique(result, entry));
        unavailable.stream()
                .sorted(Comparator.comparing(EvidenceIndexEntry::evidenceRef))
                .forEach(entry -> putUnique(result, entry));
        return result.values().stream()
                .sorted(Comparator.comparing(EvidenceIndexEntry::evidenceRef))
                .toList();
    }

    private EvidenceIndexEntry availableEvidence(Fact fact) {
        String reference = fact.evidenceRef();
        if (reference.startsWith("EMP:")) {
            int end = reference.indexOf('.');
            return new EvidenceIndexEntry(
                    reference,
                    Scope.EMPLOYEE,
                    reference.substring(4, end),
                    true
            );
        }
        if (reference.startsWith("TEAM.")) {
            return new EvidenceIndexEntry(reference, Scope.TEAM, null, true);
        }
        Scope scope = reference.contains(".CATEGORY:")
                ? Scope.CATEGORY
                : reference.contains(".ATTACH:") ? Scope.METRIC : Scope.STORE;
        return new EvidenceIndexEntry(reference, scope, null, true);
    }

    private void putUnique(
            Map<String, EvidenceIndexEntry> target,
            EvidenceIndexEntry value
    ) {
        EvidenceIndexEntry previous = target.putIfAbsent(value.evidenceRef(), value);
        require(previous == null || previous.equals(value),
                "Conflicting evidence entry: " + value.evidenceRef());
    }

    private List<String> categoryCodes(
            List<Fact> storeFacts,
            List<EmployeeFacts> employeeFacts
    ) {
        Set<String> codes = new TreeSet<>();
        storeFacts.stream()
                .map(Fact::categoryCode)
                .filter(java.util.Objects::nonNull)
                .forEach(codes::add);
        employeeFacts.stream()
                .flatMap(employee -> employee.facts().stream())
                .map(Fact::categoryCode)
                .filter(java.util.Objects::nonNull)
                .forEach(codes::add);
        return List.copyOf(codes);
    }
    private List<String> competencyCodes(
            List<Fact> storeFacts,
            List<EmployeeFacts> employeeFacts
    ) {
        List<Fact> facts = new ArrayList<>(storeFacts);
        employeeFacts.forEach(employee -> facts.addAll(employee.facts()));
        Set<String> metricCodes = new TreeSet<>();
        Set<String> categoryCodes = new TreeSet<>();
        facts.forEach(fact -> {
            metricCodes.add(fact.metricCode());
            if (fact.categoryCode() != null) {
                categoryCodes.add(fact.categoryCode());
            }
        });

        Set<String> competencies = new TreeSet<>();
        addWhenAnyMetricPresent(
                competencies,
                metricCodes,
                COMMERCIAL_METRICS,
                "COMMERCIAL_CONTRIBUTION"
        );
        addWhenAnyMetricPresent(
                competencies,
                metricCodes,
                EFFICIENCY_METRICS,
                "TIME_EFFICIENCY"
        );
        addWhenAnyMetricPresent(
                competencies,
                metricCodes,
                ADDITIONAL_SALES_METRICS,
                "ADDITIONAL_SALES"
        );
        if (facts.stream().anyMatch(fact ->
                fact.evidenceRef().contains(".ATTACH:")
                        || "RATING_ATTACH_SCORE".equals(fact.metricCode()))) {
            competencies.add("ACCESSORY_SALES");
            competencies.add("ATTACH_RATE");
        }
        if (categoryCodes.stream().anyMatch(SERVICE_CATEGORY_CODES::contains)) {
            competencies.add("SERVICE_SALES");
        }
        return List.copyOf(competencies);
    }

    private void addWhenAnyMetricPresent(
            Set<String> target,
            Set<String> actualMetrics,
            Set<String> supportingMetrics,
            String competencyCode
    ) {
        if (supportingMetrics.stream().anyMatch(actualMetrics::contains)) {
            target.add(competencyCode);
        }
    }


    private void enforceLimits(
            List<Fact> storeFacts,
            List<Fact> teamFacts,
            List<EmployeeFacts> employeeFacts
    ) {
        require(storeFacts.size() <= MAX_STORE_FACTS,
                "Weekly interpretation store facts exceed schema limit");
        require(teamFacts.size() <= MAX_TEAM_FACTS,
                "Weekly interpretation team facts exceed schema limit");
        require(employeeFacts.size() <= WeeklySnapshotPolicyV1.MAX_EMPLOYEES,
                "Weekly interpretation employees exceed schema limit");
        employeeFacts.forEach(employee -> require(
                employee.facts().size() <= MAX_EMPLOYEE_FACTS,
                "Weekly interpretation facts exceed schema limit for "
                        + employee.employeeRef()
        ));
    }
}

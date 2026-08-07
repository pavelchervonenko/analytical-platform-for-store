package com.storeanalytics.interpretation.contract;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Provider-neutral, pseudonymized contract validated by the v1 input JSON schema. */
public record WeeklyInterpretationInput(
        int contractVersion,
        Snapshot snapshot,
        Manifest manifest,
        Facts facts
) {

    public WeeklyInterpretationInput {
        require(contractVersion == 1, "contractVersion must be 1");
        requireNonNull(snapshot, "snapshot");
        requireNonNull(manifest, "manifest");
        requireNonNull(facts, "facts");
        require(snapshot.qualityStatus() != QualityStatus.BLOCKED,
                "BLOCKED snapshots must not become provider input");
    }

    public record Snapshot(
            UUID snapshotRef,
            int revision,
            String factsHash,
            String storeRef,
            String timezone,
            Period period,
            Period comparisonPeriod,
            QualityStatus qualityStatus,
            Versions versions
    ) {

        public Snapshot {
            requireNonNull(snapshotRef, "snapshotRef");
            require(revision > 0, "revision must be positive");
            requireText(factsHash, "factsHash");
            requireText(storeRef, "storeRef");
            requireText(timezone, "timezone");
            requireNonNull(period, "period");
            requireNonNull(comparisonPeriod, "comparisonPeriod");
            requireNonNull(qualityStatus, "qualityStatus");
            requireNonNull(versions, "versions");
        }
    }

    public record Period(LocalDate start, LocalDate end) {

        public Period {
            requireNonNull(start, "start");
            requireNonNull(end, "end");
            require(!end.isBefore(start), "end must not be before start");
        }
    }

    public record Versions(
            int factsSchemaVersion,
            String metricContractVersion,
            String calculationVersion,
            String qualityPolicyVersion
    ) {

        public Versions {
            require(factsSchemaVersion > 0, "factsSchemaVersion must be positive");
            requireText(metricContractVersion, "metricContractVersion");
            requireText(calculationVersion, "calculationVersion");
            requireText(qualityPolicyVersion, "qualityPolicyVersion");
        }
    }

    public record Manifest(
            List<String> employeeRefs,
            List<EvidenceIndexEntry> evidence,
            List<String> candidateRefs,
            List<String> categoryCodes,
            List<String> competencyCodes,
            List<Limitation> limitations
    ) {

        public Manifest {
            employeeRefs = copy(employeeRefs, "employeeRefs");
            evidence = copy(evidence, "evidence");
            candidateRefs = copy(candidateRefs, "candidateRefs");
            categoryCodes = copy(categoryCodes, "categoryCodes");
            competencyCodes = copy(competencyCodes, "competencyCodes");
            limitations = copy(limitations, "limitations");
        }
    }

    public record EvidenceIndexEntry(
            String evidenceRef,
            Scope scope,
            String employeeRef,
            boolean available
    ) {

        public EvidenceIndexEntry {
            requireText(evidenceRef, "evidenceRef");
            requireNonNull(scope, "scope");
        }
    }

    public record Limitation(
            String code,
            Scope scope,
            String employeeRef,
            String categoryCode,
            LimitationImpact impact,
            List<String> affectedSections,
            List<String> evidenceRefs
    ) {

        public Limitation {
            requireText(code, "code");
            requireNonNull(scope, "scope");
            requireNonNull(impact, "impact");
            affectedSections = copy(affectedSections, "affectedSections");
            evidenceRefs = copy(evidenceRefs, "evidenceRefs");
            require(!affectedSections.isEmpty(), "affectedSections must not be empty");
            require(!evidenceRefs.isEmpty(), "evidenceRefs must not be empty");
        }
    }

    public record Facts(
            List<Fact> store,
            List<Fact> team,
            List<EmployeeFacts> employees,
            List<CandidateSignal> candidateSignals
    ) {

        public Facts {
            store = copy(store, "store");
            team = copy(team, "team");
            employees = copy(employees, "employees");
            candidateSignals = copy(candidateSignals, "candidateSignals");
        }
    }

    public record Fact(
            String evidenceRef,
            String metricCode,
            String categoryCode,
            Unit unit,
            Object value,
            Comparison comparison,
            Sufficiency sufficiency,
            Materiality materiality
    ) {

        public Fact {
            requireText(evidenceRef, "evidenceRef");
            requireText(metricCode, "metricCode");
            requireNonNull(unit, "unit");
            require(value == null || value instanceof Number || value instanceof String,
                    "value must be a JSON scalar");
            requireNonNull(sufficiency, "sufficiency");
            requireNonNull(materiality, "materiality");
        }
    }

    public record Comparison(
            BigDecimal previousValue,
            BigDecimal absoluteDelta,
            BigDecimal relativeDeltaPercent
    ) {
    }

    public record EmployeeFacts(
            String employeeRef,
            Sufficiency analysisStatus,
            List<String> availableSections,
            List<Fact> facts
    ) {

        public EmployeeFacts {
            requireText(employeeRef, "employeeRef");
            requireNonNull(analysisStatus, "analysisStatus");
            availableSections = copy(availableSections, "availableSections");
            facts = copy(facts, "facts");
        }
    }

    public record CandidateSignal(
            String candidateRef,
            CandidateKind kind,
            String theme,
            String employeeRef,
            List<String> evidenceRefs
    ) {

        public CandidateSignal {
            requireText(candidateRef, "candidateRef");
            requireNonNull(kind, "kind");
            requireText(theme, "theme");
            evidenceRefs = copy(evidenceRefs, "evidenceRefs");
            require(!evidenceRefs.isEmpty(), "evidenceRefs must not be empty");
        }
    }

    public enum QualityStatus {
        READY,
        PARTIAL,
        BLOCKED
    }

    public enum Scope {
        STORE,
        TEAM,
        EMPLOYEE,
        CATEGORY,
        METRIC
    }

    public enum LimitationImpact {
        REDUCED_CONFIDENCE,
        UNAVAILABLE
    }

    public enum Unit {
        MONEY,
        COUNT,
        PERCENT,
        RATE_PER_HUNDRED,
        HOURS,
        SCORE,
        RANK,
        STATUS
    }

    public enum Sufficiency {
        SUFFICIENT,
        LIMITED,
        INSUFFICIENT
    }

    public enum Materiality {
        PRIMARY,
        SECONDARY,
        CONTEXT
    }

    public enum CandidateKind {
        OBSERVATION,
        RISK,
        OPPORTUNITY
    }

    private static <T> List<T> copy(List<T> values, String name) {
        return List.copyOf(requireNonNull(values, name));
    }
}

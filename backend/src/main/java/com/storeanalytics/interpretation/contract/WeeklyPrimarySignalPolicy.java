package com.storeanalytics.interpretation.contract;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.CandidateSignal;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.EvidenceIndexEntry;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selects the single backend-prioritized candidate used by content schema v3. */
public final class WeeklyPrimarySignalPolicy {

    private static final Set<String> RELATIONSHIP_THEMES = Set.of(
            "COMPETENCY_LEADER",
            "MOST_IMPROVED",
            "LEARNING_OPPORTUNITY"
    );

    private WeeklyPrimarySignalPolicy() {
    }

    public static List<CandidateSignal> orderedStoreCandidates(
            WeeklyInterpretationInput input
    ) {
        Map<String, EvidenceIndexEntry> evidence = new HashMap<>();
        input.manifest().evidence().forEach(value ->
                evidence.put(value.evidenceRef(), value)
        );
        return input.facts().candidateSignals().stream()
                .filter(candidate -> !RELATIONSHIP_THEMES.contains(
                        candidate.theme()
                ))
                .filter(candidate -> candidate.employeeRef() == null)
                .filter(candidate -> candidate.evidenceRefs().stream()
                        .map(evidence::get)
                        .anyMatch(value -> value == null
                                || value.scope()
                                != WeeklyInterpretationInput.Scope.TEAM))
                .sorted(Comparator
                        .comparingInt(WeeklyPrimarySignalPolicy::sufficiencyPriority)
                        .thenComparingInt(
                                WeeklyPrimarySignalPolicy::themePriority
                        )
                        .thenComparing(CandidateSignal::candidateRef))
                .toList();
    }

    private static int sufficiencyPriority(CandidateSignal candidate) {
        return switch (candidate.sufficiency()) {
            case SUFFICIENT -> 0;
            case LIMITED -> 1;
            case INSUFFICIENT -> 2;
        };
    }

    private static int themePriority(CandidateSignal candidate) {
        return switch (candidate.theme()) {
            case "PLAN" -> 0;
            case "PROFITABILITY" -> 1;
            case "REVENUE_DYNAMICS" -> 2;
            case "ADDITIONAL_SALES" -> 3;
            case "ATTACH_RATE" -> 4;
            case "CATEGORY_MIX" -> 5;
            case "TEAM_PERFORMANCE" -> 6;
            default -> 7;
        };
    }
}

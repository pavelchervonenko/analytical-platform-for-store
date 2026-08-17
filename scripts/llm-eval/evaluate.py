#!/usr/bin/env python3
"""Deterministic dataset and response gate for weekly LLM interpretations."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import sys
import uuid
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


EVALUATION_NAMESPACE = uuid.UUID("1eb814c7-8420-4aaf-8bf6-f915a3b54627")
NARRATIVE_FIELDS = frozenset({"text", "title", "summary"})


def load_json(path: Path) -> object:
    with path.open("r", encoding="utf-8") as source:
        return json.load(source)


def repository_path(repository: Path, value: str) -> Path:
    resolved = (repository / value).resolve()
    try:
        resolved.relative_to(repository)
    except ValueError as exception:
        raise ValueError(f"path escapes repository: {value}") from exception
    return resolved


def strings(node: object, path: str = "$", field: str | None = None):
    if isinstance(node, dict):
        for key, value in node.items():
            yield from strings(value, f"{path}.{key}", key)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from strings(value, f"{path}[{index}]", field)
    elif isinstance(node, str):
        yield path, field, node


def values_for_key(node: object, target: str):
    if isinstance(node, dict):
        for key, value in node.items():
            if key == target:
                yield value
            yield from values_for_key(value, target)
    elif isinstance(node, list):
        for value in node:
            yield from values_for_key(value, target)


def json_pointer(node: object, pointer: str) -> object:
    value = node
    for part in pointer.removeprefix("/").split("/") if pointer != "/" else []:
        token = part.replace("~1", "/").replace("~0", "~")
        value = value[int(token)] if isinstance(value, list) else value[token]
    return value


def evidence_refs(output: object, exclude_limitations: bool = False) -> set[str]:
    result: set[str] = set()
    if isinstance(output, dict):
        for key, value in output.items():
            if exclude_limitations and key == "dataLimitations":
                continue
            if key == "evidenceRefs" and isinstance(value, list):
                result.update(item for item in value if isinstance(item, str))
            else:
                result.update(evidence_refs(value, exclude_limitations))
    elif isinstance(output, list):
        for value in output:
            result.update(evidence_refs(value, exclude_limitations))
    return result


def schema_failures(
    validator: Draft202012Validator,
    payload: object,
    prefix: str,
) -> list[str]:
    return [
        f"{prefix}: schema {error.json_path}: {error.message}"
        for error in sorted(
            validator.iter_errors(payload),
            key=lambda item: (item.json_path, item.message),
        )
    ]


def deep_merge(base: object, override: object) -> object:
    if isinstance(base, dict) and isinstance(override, dict):
        result = copy.deepcopy(base)
        for key, value in override.items():
            result[key] = deep_merge(result[key], value) if key in result else copy.deepcopy(value)
        return result
    return copy.deepcopy(override)


def normalize_fact(source: dict) -> tuple[dict, bool]:
    fact = {
        "evidenceRef": source["evidenceRef"],
        "metricCode": source["metricCode"],
        "categoryCode": source.get("categoryCode"),
        "unit": source["unit"],
        "value": source.get("value"),
        "comparison": source.get("comparison"),
        "sufficiency": source.get("sufficiency", "SUFFICIENT"),
        "materiality": source.get("materiality", "CONTEXT"),
    }
    return fact, source.get("available", True)


def normalize_candidate(source: dict) -> dict:
    return {
        "candidateRef": source["candidateRef"],
        "kind": source["kind"],
        "theme": source["theme"],
        "employeeRef": source.get("employeeRef"),
        "categoryCode": source.get("categoryCode"),
        "competencyCode": source.get("competencyCode"),
        "targetEmployeeRefs": source.get("targetEmployeeRefs", []),
        "sufficiency": source.get("sufficiency", "SUFFICIENT"),
        "evidenceRefs": source["evidenceRefs"],
    }


def build_input(dataset: dict, case: dict) -> dict:
    scenario = deep_merge(dataset.get("defaults", {}), case["scenario"])
    if not isinstance(scenario, dict):
        raise ValueError("scenario must be an object")

    evidence: list[dict] = []
    seen_evidence: set[str] = set()

    def add_evidence(reference: str, scope: str, employee_ref: str | None, available: bool):
        if reference in seen_evidence:
            raise ValueError(f"duplicate evidenceRef: {reference}")
        seen_evidence.add(reference)
        evidence.append({
            "evidenceRef": reference,
            "scope": scope,
            "employeeRef": employee_ref,
            "available": available,
        })

    store_facts: list[dict] = []
    for raw in scenario.get("storeFacts", []):
        fact, available = normalize_fact(raw)
        store_facts.append(fact)
        add_evidence(fact["evidenceRef"], "STORE", None, available)

    team_facts: list[dict] = []
    for raw in scenario.get("teamFacts", []):
        fact, available = normalize_fact(raw)
        team_facts.append(fact)
        add_evidence(fact["evidenceRef"], "TEAM", None, available)

    employees: list[dict] = []
    for raw_employee in scenario.get("employees", []):
        employee_ref = raw_employee["employeeRef"]
        facts: list[dict] = []
        for raw in raw_employee.get("facts", []):
            fact, available = normalize_fact(raw)
            facts.append(fact)
            add_evidence(fact["evidenceRef"], "EMPLOYEE", employee_ref, available)
        employees.append({
            "employeeRef": employee_ref,
            "analysisStatus": raw_employee["analysisStatus"],
            "availableSections": raw_employee.get("availableSections", []),
            "facts": facts,
        })

    for unavailable in scenario.get("unavailableEvidence", []):
        add_evidence(
            unavailable["evidenceRef"],
            unavailable["scope"],
            unavailable.get("employeeRef"),
            False,
        )

    candidates = [
        normalize_candidate(candidate)
        for candidate in scenario.get("candidates", [])
    ]
    limitations = copy.deepcopy(scenario.get("limitations", []))
    category_labels = copy.deepcopy(scenario.get("categoryLabels", {}))
    category_codes = set(category_labels)
    competency_codes: set[str] = set()
    for fact in store_facts + team_facts:
        if fact["categoryCode"]:
            category_codes.add(fact["categoryCode"])
    for employee in employees:
        for fact in employee["facts"]:
            if fact["categoryCode"]:
                category_codes.add(fact["categoryCode"])
    for candidate in candidates:
        if candidate["categoryCode"]:
            category_codes.add(candidate["categoryCode"])
        if candidate["competencyCode"]:
            competency_codes.add(candidate["competencyCode"])
    for limitation in limitations:
        if limitation.get("categoryCode"):
            category_codes.add(limitation["categoryCode"])

    scenario_json = json.dumps(
        scenario,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    facts_hash = hashlib.sha256(scenario_json.encode("utf-8")).hexdigest()
    case_id = case["id"]
    snapshot_ref = str(uuid.uuid5(EVALUATION_NAMESPACE, case_id))
    period = scenario.get("period", {
        "start": "2026-08-03",
        "end": "2026-08-09",
    })
    comparison_period = scenario.get("comparisonPeriod", {
        "start": "2026-07-27",
        "end": "2026-08-02",
    })
    return {
        "contractVersion": 1,
        "snapshot": {
            "snapshotRef": snapshot_ref,
            "revision": 1,
            "factsHash": facts_hash,
            "storeRef": "S01",
            "timezone": scenario.get("timezone", "Europe/Moscow"),
            "period": period,
            "comparisonPeriod": comparison_period,
            "qualityStatus": scenario.get("qualityStatus", "READY"),
            "versions": {
                "factsSchemaVersion": 1,
                "metricContractVersion": "weekly-metrics-v3",
                "calculationVersion": "weekly-snapshot-v6",
                "qualityPolicyVersion": "weekly-quality-v3",
            },
        },
        "manifest": {
            "employeeRefs": [employee["employeeRef"] for employee in employees],
            "evidence": evidence,
            "candidateRefs": [
                candidate["candidateRef"] for candidate in candidates
            ],
            "categoryCodes": sorted(category_codes),
            "categoryLabels": category_labels,
            "competencyCodes": sorted(competency_codes),
            "limitations": limitations,
        },
        "facts": {
            "store": store_facts,
            "team": team_facts,
            "employees": employees,
            "candidateSignals": candidates,
        },
    }


def narrative_values(output: object):
    for path, field, value in strings(output):
        if field in NARRATIVE_FIELDS and ".dataLimitations" not in path:
            yield path, value


def normalized_text(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip().casefold()


def duplicate_narrative_groups(output: dict) -> dict[str, list[str]]:
    grouped: dict[str, list[str]] = {}
    for path, value in narrative_values(output):
        normalized = normalized_text(value)
        if normalized:
            grouped.setdefault(normalized, []).append(path)
    summaries = output.get("summaryBlocks", [])

    def mandatory_employee_headlines(paths: list[str]) -> bool:
        employee_refs = []
        for path in paths:
            match = re.fullmatch(r"\$\.summaryBlocks\[(\d+)]\.text", path)
            if match is None:
                return False
            index = int(match.group(1))
            if index >= len(summaries):
                return False
            summary = summaries[index]
            if (
                not isinstance(summary, dict)
                or summary.get("scope") != "EMPLOYEE"
                or summary.get("section") != "HEADLINE"
                or not summary.get("employeeRef")
            ):
                return False
            employee_refs.append(summary["employeeRef"])
        return len(employee_refs) == len(set(employee_refs))

    return {
        text: paths
        for text, paths in grouped.items()
        if len(paths) > 1 and not mandatory_employee_headlines(paths)
    }


ACTION_TOKEN_PATTERN = re.compile(r"[a-zа-яё]+", re.IGNORECASE)
ACTION_STOP_WORDS = frozenset({
    "а",
    "без",
    "в",
    "для",
    "до",
    "и",
    "из",
    "или",
    "к",
    "на",
    "но",
    "о",
    "от",
    "по",
    "при",
    "с",
    "со",
    "у",
})

REVENUE_NARRATIVE_PATTERN = re.compile(
    r"(?:\u0432\u044b\u0440\u0443\u0447|"
    r"\u043e\u0431\u043e\u0440\u043e\u0442|"
    r"\u0434\u043e\u0445\u043e\u0434(?:\u0430|\u0443|\u043e\u043c|\u0435|\u044b|\u043e\u0432|\u0430\u043c|\u0430\u043c\u0438|\u0430\u0445)?\b)",
    re.IGNORECASE,
)
PROFITABILITY_NARRATIVE_PATTERN = re.compile(
    r"(?:\u043f\u0440\u0438\u0431\u044b\u043b|"
    r"\u043c\u0430\u0440\u0436|"
    r"\u0440\u0435\u043d\u0442\u0430\u0431\u0435\u043b|"
    r"\u0434\u043e\u0445\u043e\u0434\u043d|\u0437\u0430\u0440\u0430\u0431\u043e\u0442)",
    re.IGNORECASE,
)
REVENUE_METRIC_FRAGMENTS = (
    "NET_REVENUE",
    "REVENUE_SHARE",
    "ADDITIONAL_REVENUE",
    "REVENUE_PER",
)
PROFITABILITY_METRIC_FRAGMENTS = (
    "GROSS_PROFIT",
    "MARGIN",
    "PROFIT",
)


def action_text(action: dict) -> str:
    return " ".join(
        value
        for field in ("title", "summary")
        if isinstance((value := action.get(field)), str)
    )


def narrative_tokens(value: str) -> set[str]:
    tokens = ACTION_TOKEN_PATTERN.findall(
        value.casefold().replace("ё", "е")
    )
    return {
        token[:5] if len(token) >= 6 else token
        for token in tokens
        if token not in ACTION_STOP_WORDS
    }


def action_tokens(action: dict) -> set[str]:
    return narrative_tokens(action_text(action))


def dice_similarity(left: set[str], right: set[str]) -> float:
    if not left or not right:
        return 0.0
    return 2 * len(left & right) / (len(left) + len(right))


def containment_similarity(left: set[str], right: set[str]) -> float:
    if not left or not right:
        return 0.0
    return len(left & right) / min(len(left), len(right))


def string_tuple(value: object) -> tuple[str, ...]:
    if not isinstance(value, list):
        return ()
    return tuple(sorted(
        item for item in value if isinstance(item, str)
    ))


def action_core(action: dict) -> tuple:
    return (
        action.get("targetScope"),
        string_tuple(action.get("targetEmployeeRefs")),
        action.get("horizon"),
        string_tuple(action.get("evidenceRefs")),
    )


def action_quality(dataset: dict, output: dict) -> dict:
    indexed_actions = [
        (index, action)
        for index, action in enumerate(output.get("actions", []))
        if isinstance(action, dict)
    ]
    policy = dataset.get("actionQuality", {})
    specificity_patterns = [
        re.compile(pattern, re.IGNORECASE)
        for pattern in policy.get("specificityPatterns", [])
    ]
    boilerplate_patterns = [
        re.compile(pattern, re.IGNORECASE)
        for pattern in policy.get("forbiddenBoilerplatePatterns", [])
    ]
    non_specific = {
        index
        for index, action in indexed_actions
        if (
            specificity_patterns
            and not any(
                pattern.search(action_text(action))
                for pattern in specificity_patterns
            )
        )
        or any(
            pattern.search(action_text(action))
            for pattern in boilerplate_patterns
        )
    }
    strong_threshold = policy.get("nearDuplicateSimilarity", 0.72)
    weak_threshold = policy.get(
        "nonSpecificNearDuplicateSimilarity", 0.45
    )
    duplicate_pairs = []
    tokens = {
        index: action_tokens(action)
        for index, action in indexed_actions
    }
    for position, (left_index, left) in enumerate(indexed_actions):
        for right_index, right in indexed_actions[position + 1:]:
            if action_core(left) != action_core(right):
                continue
            similarity = dice_similarity(
                tokens[left_index], tokens[right_index]
            )
            same_type = left.get("type") == right.get("type")
            involves_non_specific = (
                left_index in non_specific or right_index in non_specific
            )
            if (
                same_type
                or similarity >= strong_threshold
                or (
                    involves_non_specific
                    and similarity >= weak_threshold
                )
            ):
                duplicate_pairs.append(
                    (left_index, right_index, round(similarity, 4))
                )
    return {
        "nonSpecificIndexes": non_specific,
        "nearDuplicatePairs": duplicate_pairs,
    }


def narrative_quality(dataset: dict, output: dict) -> dict:
    policy = dataset.get("narrativeQuality", {})
    dice_threshold = policy.get("headlineInsightSimilarity", 1.0)
    containment_threshold = policy.get("headlineInsightContainment", 1.0)
    primary_team_dice_threshold = policy.get(
        "primaryTeamOverviewSimilarity", dice_threshold
    )
    primary_team_containment_threshold = policy.get(
        "primaryTeamOverviewContainment", containment_threshold
    )
    directive_patterns = [
        re.compile(pattern, re.IGNORECASE)
        for pattern in policy.get("insightDirectivePatterns", [])
    ]
    cause_patterns = [
        re.compile(pattern, re.IGNORECASE)
        for pattern in policy.get("unsupportedCausePatterns", [])
    ]
    summaries = [
        (index, summary)
        for index, summary in enumerate(output.get("summaryBlocks", []))
        if isinstance(summary, dict)
        and summary.get("scope") == "STORE"
        and summary.get("section") == "HEADLINE"
    ]
    team_overviews = [
        (index, summary)
        for index, summary in enumerate(output.get("summaryBlocks", []))
        if isinstance(summary, dict)
        and summary.get("scope") == "TEAM"
        and summary.get("section") == "TEAM_OVERVIEW"
    ]
    all_insights = [
        (index, insight)
        for index, insight in enumerate(output.get("insights", []))
        if isinstance(insight, dict)
    ]
    store_insights = [
        (index, insight)
        for index, insight in all_insights
        if insight.get("scope") == "STORE"
    ]
    near_duplicates = []
    for summary_index, summary in summaries:
        summary_evidence = set(summary.get("evidenceRefs", []))
        summary_tokens = narrative_tokens(summary.get("text", ""))
        for insight_index, insight in store_insights:
            insight_evidence = set(insight.get("evidenceRefs", []))
            if not summary_evidence.intersection(insight_evidence):
                continue
            insight_tokens = narrative_tokens(insight.get("title", ""))
            dice = dice_similarity(summary_tokens, insight_tokens)
            containment = containment_similarity(
                summary_tokens, insight_tokens
            )
            if (
                dice >= dice_threshold
                or containment >= containment_threshold
            ):
                near_duplicates.append((
                    summary_index,
                    insight_index,
                    round(dice, 4),
                    round(containment, 4),
                ))
    near_primary_duplicates = []
    near_primary_team_overviews = []
    primary_signal = output.get("primarySignal")
    if isinstance(primary_signal, dict):
        primary_tokens = narrative_tokens(primary_signal.get("text", ""))
        for insight_index, insight in store_insights:
            insight_tokens = narrative_tokens(insight.get("title", ""))
            dice = dice_similarity(primary_tokens, insight_tokens)
            containment = containment_similarity(
                primary_tokens, insight_tokens
            )
            if (
                dice >= dice_threshold
                or containment >= containment_threshold
            ):
                near_primary_duplicates.append((
                    insight_index,
                    round(dice, 4),
                    round(containment, 4),
                ))
        for summary_index, summary in team_overviews:
            summary_tokens = narrative_tokens(summary.get("text", ""))
            dice = dice_similarity(primary_tokens, summary_tokens)
            containment = containment_similarity(
                primary_tokens, summary_tokens
            )
            if (
                dice >= primary_team_dice_threshold
                or containment >= primary_team_containment_threshold
            ):
                near_primary_team_overviews.append((
                    summary_index, round(dice, 4), round(containment, 4)
                ))
    directive_insights = {
        index
        for index, insight in all_insights
        if any(
            pattern.search(action_text(insight))
            for pattern in directive_patterns
        )
    }
    unsupported_causes = []
    for collection, field in (
        ("summaryBlocks", "text"),
        ("teamRelationships", "summary"),
    ):
        for index, value in enumerate(output.get(collection, [])):
            if (
                isinstance(value, dict)
                and any(
                    pattern.search(value.get(field, ""))
                    for pattern in cause_patterns
                )
            ):
                unsupported_causes.append((collection, index))
    unsupported_causes.extend(
        ("insights", index)
        for index, insight in all_insights
        if insight.get("kind") != "HYPOTHESIS"
        and any(
            pattern.search(action_text(insight))
            for pattern in cause_patterns
        )
    )
    return {
        "nearDuplicateHeadlineInsightPairs": near_duplicates,
        "nearDuplicatePrimaryInsightPairs": near_primary_duplicates,
        "nearDuplicatePrimaryTeamOverviewPairs":
            near_primary_team_overviews,
        "directiveInsightIndexes": directive_insights,
        "unsupportedCauseItems": unsupported_causes,
    }


def team_overview_evidence_failures(
    prefix: str,
    output: dict,
    payload: dict,
) -> list[str]:
    evidence_scopes = {
        item["evidenceRef"]: item.get("scope")
        for item in payload["manifest"]["evidence"]
        if isinstance(item, dict)
        and isinstance(item.get("evidenceRef"), str)
    }
    failures = []
    for index, summary in enumerate(output.get("summaryBlocks", [])):
        if not (
            isinstance(summary, dict)
            and summary.get("scope") == "TEAM"
            and summary.get("section") == "TEAM_OVERVIEW"
        ):
            continue
        expected = backend_team_overview(payload)
        if (
            expected is not None
            and summary.get("text") == expected["text"]
            and summary.get("evidenceRefs") == expected["evidenceRefs"]
        ):
            continue
        invalid = sorted(
            reference
            for reference in summary.get("evidenceRefs", [])
            if evidence_scopes.get(reference) != "TEAM"
        )
        if invalid:
            failures.append(
                f"{prefix}: TEAM overview cites non-TEAM evidence at "
                f"$.summaryBlocks[{index}]: {invalid}"
            )
    return failures


def evidence_metric_codes(payload: dict) -> dict[str, str]:
    facts = payload.get("facts", {})
    values = [
        *facts.get("store", []),
        *facts.get("team", []),
        *(
            fact
            for employee in facts.get("employees", [])
            for fact in employee.get("facts", [])
        ),
    ]
    return {
        fact["evidenceRef"]: fact.get("metricCode", "")
        for fact in values
        if isinstance(fact, dict) and isinstance(fact.get("evidenceRef"), str)
    }


def evidence_supports_dimension(
    reference: str,
    metric_codes: dict[str, str],
    fragments: tuple[str, ...],
    extra_reference_fragments: tuple[str, ...] = (),
) -> bool:
    metric_code = metric_codes.get(reference, "")
    return any(fragment in metric_code for fragment in fragments) or any(
        fragment in reference
        for fragment in (*fragments, *extra_reference_fragments)
    )


def narrative_dimension_failures(
    prefix: str,
    output: dict,
    payload: dict,
) -> list[str]:
    failures: list[str] = []
    metric_codes = evidence_metric_codes(payload)
    collections = (
        ("primarySignal", ("text",)),
        ("summaryBlocks", ("text",)),
        ("insights", ("title", "summary")),
        ("actions", ("title", "summary")),
        ("teamRelationships", ("summary",)),
    )
    for collection, fields in collections:
        value = output.get(collection)
        items = [value] if collection == "primarySignal" else (value or [])
        for index, item in enumerate(items):
            if not isinstance(item, dict):
                continue
            narrative = " ".join(
                value
                for field in fields
                if isinstance((value := item.get(field)), str)
            )
            references = [
                reference
                for reference in item.get("evidenceRefs", [])
                if isinstance(reference, str)
            ]
            path = (
                "$.primarySignal"
                if collection == "primarySignal"
                else f"$.{collection}[{index}]"
            )
            if REVENUE_NARRATIVE_PATTERN.search(narrative) and not any(
                evidence_supports_dimension(
                    reference,
                    metric_codes,
                    REVENUE_METRIC_FRAGMENTS,
                    ("PLAN:REVENUE",),
                )
                for reference in references
            ):
                failures.append(
                    f"{prefix}: unsupported REVENUE narrative dimension "
                    f"at {path}"
                )
            if PROFITABILITY_NARRATIVE_PATTERN.search(narrative) and not any(
                evidence_supports_dimension(
                    reference,
                    metric_codes,
                    PROFITABILITY_METRIC_FRAGMENTS,
                )
                for reference in references
            ):
                failures.append(
                    f"{prefix}: unsupported PROFITABILITY narrative dimension "
                    f"at {path}"
                )
    return failures


def selector_matches(value: object, selector: dict) -> bool:
    return isinstance(value, dict) and all(
        value.get(key) == expected for key, expected in selector.items()
    )


def relationship_selector_matches(value: object, selector: dict) -> bool:
    if not isinstance(value, dict):
        return False
    unordered_fields = {"sourceEmployeeRefs", "targetEmployeeRefs"}
    return all(
        set(value.get(key, [])) == set(expected)
        if key in unordered_fields
        else value.get(key) == expected
        for key, expected in selector.items()
    )


RELATIONSHIP_TYPES = {
    "COMPETENCY_LEADER",
    "MOST_IMPROVED",
    "LEARNING_OPPORTUNITY",
}

STORE_CANDIDATE_THEME_PRIORITY = {
    "PLAN": 0,
    "PROFITABILITY": 1,
    "REVENUE_DYNAMICS": 2,
    "ADDITIONAL_SALES": 3,
    "ATTACH_RATE": 4,
    "CATEGORY_MIX": 5,
    "TEAM_PERFORMANCE": 6,
}
SUFFICIENCY_PRIORITY = {
    "SUFFICIENT": 0,
    "LIMITED": 1,
    "INSUFFICIENT": 2,
}


def store_candidates(payload: dict) -> list[dict]:
    evidence = {
        item["evidenceRef"]: item
        for item in payload["manifest"]["evidence"]
    }
    candidates = [
        candidate
        for candidate in payload["facts"]["candidateSignals"]
        if candidate["theme"] not in RELATIONSHIP_TYPES
        and candidate["employeeRef"] is None
        and any(
            evidence.get(reference, {}).get("scope") != "TEAM"
            for reference in candidate["evidenceRefs"]
        )
    ]
    return sorted(candidates, key=lambda candidate: (
        SUFFICIENCY_PRIORITY.get(candidate["sufficiency"], 3),
        STORE_CANDIDATE_THEME_PRIORITY.get(candidate["theme"], 7),
        candidate["candidateRef"],
    ))


PRIVACY_STORE_CORE_METRICS = {
    "NET_REVENUE",
    "GROSS_PROFIT",
    "MARGIN_PERCENT",
    "AVERAGE_RECEIPT",
    "ADDITIONAL_REVENUE_PER_PHONE",
}
PRIVACY_PLAN_METRICS = {
    "PLAN_ACTUAL_AMOUNT",
    "PLAN_PROJECTED_COMPLETION_PERCENT",
}
PRIVACY_CATEGORY_METRICS = {"NET_REVENUE", "REVENUE_SHARE_PERCENT"}
PRIVACY_ATTACH_METRICS = {
    "NUMERATOR_QUANTITY",
    "DENOMINATOR_QUANTITY",
    "RATE_PER_HUNDRED",
}


def compact_identifier(reference: str, marker: str) -> str:
    start = reference.index(marker)
    end = reference.index(".", start + len(marker))
    return reference[start + len(marker):end]


def top_compact_identifiers(
    facts: list[dict],
    marker: str,
    metric_code: str,
    limit: int,
) -> set[str]:
    scored: dict[str, float] = {}
    for fact in facts:
        reference = fact["evidenceRef"]
        if f".{marker}" not in reference or fact["metricCode"] != metric_code:
            continue
        identifier = compact_identifier(reference, marker)
        comparison = fact.get("comparison") or {}
        current = abs(float(fact["value"]))
        previous = abs(float(comparison.get("previousValue") or 0))
        scored.setdefault(identifier, max(current, previous))
    return {
        identifier
        for identifier, _ in sorted(
            scored.items(),
            key=lambda item: (-item[1], item[0]),
        )[:limit]
    }


def privacy_reduced_provider_allowlists(
    payload: dict,
) -> tuple[set[str], set[str], set[str]]:
    evidence_scopes = {
        item["evidenceRef"]: item["scope"]
        for item in payload["manifest"]["evidence"]
    }
    candidates = [
        candidate
        for candidate in payload["facts"]["candidateSignals"]
        if candidate["employeeRef"] is None
        and not candidate["targetEmployeeRefs"]
        and all(
            evidence_scopes.get(reference) not in {"EMPLOYEE", "TEAM"}
            for reference in candidate["evidenceRefs"]
        )
    ]
    referenced_evidence = {
        reference
        for candidate in candidates
        for reference in candidate["evidenceRefs"]
    }
    source = payload["facts"]["store"]
    categories = top_compact_identifiers(
        source, "CATEGORY:", "NET_REVENUE", 2
    )
    attach = top_compact_identifiers(
        source, "ATTACH:", "DENOMINATOR_QUANTITY", 1
    )

    def keep_store(fact: dict) -> bool:
        reference = fact["evidenceRef"]
        metric = fact["metricCode"]
        if ".CATEGORY:" in reference:
            return (
                compact_identifier(reference, "CATEGORY:") in categories
                and metric in PRIVACY_CATEGORY_METRICS
            )
        if ".GROUP:" in reference:
            return False
        if ".ATTACH:" in reference:
            return (
                compact_identifier(reference, "ATTACH:") in attach
                and metric in PRIVACY_ATTACH_METRICS
            )
        if ".PLAN:" in reference:
            return metric in PRIVACY_PLAN_METRICS
        return (
            metric in PRIVACY_STORE_CORE_METRICS
            or fact["materiality"] == "PRIMARY"
        )

    retained_store = [
        fact for fact in source
        if keep_store(fact)
        or fact["evidenceRef"] in referenced_evidence
    ]
    retained_team = [
        fact for fact in payload["facts"]["team"]
        if fact["metricCode"] == "RATING_ELIGIBLE_COUNT"
    ]
    evidence_refs = referenced_evidence | {
        fact["evidenceRef"]
        for fact in [*retained_store, *retained_team]
    }
    category_codes = {
        fact["categoryCode"]
        for fact in retained_store
        if fact.get("categoryCode") is not None
    }
    return (
        {candidate["candidateRef"] for candidate in candidates},
        evidence_refs,
        category_codes,
    )


def privacy_reduced_provider_failures(
    prefix: str,
    output: dict,
    payload: dict,
) -> list[str]:
    failures: list[str] = []
    for field in (
        "employees",
        "employeeHeadlines",
        "summaryBlocks",
        "dataLimitations",
    ):
        if field in output:
            failures.append(
                f"{prefix}: backend-owned provider field at $.{field}"
            )
    if output.get("teamRelationships"):
        failures.append(
            f"{prefix}: provider relationship is not allowed"
        )
    candidate_refs, evidence_refs_allowed, category_codes = (
        privacy_reduced_provider_allowlists(payload)
    )

    def visit(node: object, path: str) -> None:
        if isinstance(node, dict):
            for field, value in node.items():
                child = f"{path}.{field}"
                if (
                    field == "candidateRef"
                    and isinstance(value, str)
                    and value not in candidate_refs
                ):
                    failures.append(
                        f"{prefix}: provider candidate was not sent at {child}"
                    )
                elif (
                    field == "categoryCode"
                    and isinstance(value, str)
                    and value not in category_codes
                ):
                    failures.append(
                        f"{prefix}: provider category was not sent at {child}"
                    )
                elif field == "employeeRef" and value is not None:
                    failures.append(
                        f"{prefix}: provider employee reference at {child}"
                    )
                elif (
                    field in {"sourceEmployeeRefs", "targetEmployeeRefs"}
                    and isinstance(value, list)
                    and value
                ):
                    failures.append(
                        f"{prefix}: provider employee reference at {child}"
                    )
                elif field == "evidenceRefs" and isinstance(value, list):
                    for index, reference in enumerate(value):
                        if (
                            isinstance(reference, str)
                            and reference not in evidence_refs_allowed
                        ):
                            failures.append(
                                f"{prefix}: provider evidence was not sent "
                                f"at {child}[{index}]"
                            )
                visit(value, child)
        elif isinstance(node, list):
            for index, value in enumerate(node):
                visit(value, f"{path}[{index}]")

    visit(output, "$")
    return failures



CANONICAL_LIMITATION_SECTIONS = {
    "CATEGORIES": "CATEGORY_PERFORMANCE",
    "ATTACH": "ADDITIONAL_SALES",
    "PROFIT": "PROFITABILITY",
    "MARGIN": "PROFITABILITY",
    "EMPLOYEES": "TEAM_COMPARISON",
}


def backend_normalize_response(output: dict, payload: dict) -> dict:
    """Apply deterministic v2 normalization performed before backend validation."""
    normalized = copy.deepcopy(output)
    if normalized.pop("backendEmployeeHeadlines", False) is True:
        normalized["employeeHeadlines"] = backend_employee_headlines(payload)

    structured_transport = any(
        field in normalized
        for field in (
            "teamOverview",
            "employeeHeadlines",
            "supportingSummaries",
        )
    )
    if structured_transport:
        team_overview = normalized.get("teamOverview")
        if isinstance(team_overview, dict):
            backend_overview = backend_team_overview(payload)
            if backend_overview is not None:
                normalized["teamOverview"] = backend_overview
        normalized["employees"] = [
            {
                "employeeRef": employee["employeeRef"],
                "analysisStatus": employee["analysisStatus"],
            }
            for employee in payload["facts"]["employees"]
        ]
        summaries = []
        store_summary = backend_store_summary(payload)
        if store_summary is not None:
            summaries.append(store_summary)
        team_overview = normalized.get("teamOverview")
        if isinstance(team_overview, dict):
            summaries.append({
                "scope": "TEAM",
                "employeeRef": None,
                "section": "TEAM_OVERVIEW",
                "categoryCode": None,
                "text": team_overview.get("text"),
                "evidenceRefs": team_overview.get("evidenceRefs"),
            })
        employee_headlines = normalized.get("employeeHeadlines", {})
        if isinstance(employee_headlines, dict):
            for employee_ref in payload["manifest"]["employeeRefs"]:
                headline = employee_headlines.get(employee_ref)
                if isinstance(headline, dict):
                    summaries.append({
                        "scope": "EMPLOYEE",
                        "employeeRef": employee_ref,
                        "section": "HEADLINE",
                        "categoryCode": None,
                        "text": headline.get("text"),
                        "evidenceRefs": headline.get("evidenceRefs"),
                    })
        supporting = normalized.get("supportingSummaries", [])
        if isinstance(supporting, list):
            summaries.extend(copy.deepcopy(supporting))
        normalized["summaryBlocks"] = summaries
        normalized.pop("teamOverview", None)
        normalized.pop("employeeHeadlines", None)
        normalized.pop("supportingSummaries", None)
        normalized["teamRelationships"] = [
            {
                **candidate_relationship(candidate),
                "summary": relationship_summary(candidate["theme"]),
                "evidenceRefs": list(candidate["evidenceRefs"]),
            }
            for candidate in payload["facts"]["candidateSignals"]
            if candidate_relationship(candidate) is not None
        ]

    for summary in normalized.get("summaryBlocks", []):
        if isinstance(summary, dict):
            summary.setdefault("employeeRef", None)
            summary.setdefault("categoryCode", None)
    candidate_items = []
    for insight in normalized.get("insights", []):
        if isinstance(insight, dict):
            insight.setdefault("employeeRef", None)
            insight.setdefault("categoryCode", None)
            insight.setdefault("candidateRef", None)
            candidate_items.append(insight)
    primary_signal = normalized.get("primarySignal")
    if isinstance(primary_signal, dict):
        primary_signal.setdefault("employeeRef", None)
        primary_signal.setdefault("categoryCode", None)
        candidate_items.append(primary_signal)
    candidates = {
        candidate["candidateRef"]: candidate
        for candidate in payload["facts"]["candidateSignals"]
    }
    evidence = {
        item["evidenceRef"]: item
        for item in payload["manifest"]["evidence"]
    }
    for item in candidate_items:
        candidate = candidates.get(item.get("candidateRef"))
        if candidate is None:
            continue
        item["kind"] = candidate["kind"]
        item["theme"] = candidate["theme"]
        item["employeeRef"] = candidate["employeeRef"]
        item["categoryCode"] = candidate["categoryCode"]
        if candidate["employeeRef"] is not None:
            item["scope"] = "EMPLOYEE"
        elif all(
            evidence.get(reference, {}).get("scope") == "TEAM"
            for reference in candidate["evidenceRefs"]
        ):
            item["scope"] = "TEAM"
        else:
            item["scope"] = "STORE"
        item["evidenceRefs"] = list(candidate["evidenceRefs"])
        if structured_transport:
            item["evidenceRefs"] = candidate_evidence_refs(
                candidate, payload
            )
            title, summary = candidate_narrative(candidate, payload)
            if "text" in item:
                item["text"] = summary
            else:
                item["title"] = title
                item["summary"] = summary
    for relationship in normalized.get("teamRelationships", []):
        if isinstance(relationship, dict):
            relationship.setdefault("competencyCode", None)
    for action in normalized.get("actions", []):
        if (
            isinstance(action, dict)
            and action.get("targetScope") in {"STORE", "TEAM"}
            and isinstance(action.get("targetEmployeeRefs"), list)
        ):
            action["targetEmployeeRefs"] = []

    limitations = []
    for source in payload["manifest"]["limitations"]:
        sections = {
            CANONICAL_LIMITATION_SECTIONS.get(section, section)
            for section in source["affectedSections"]
        }
        limitations.append({
            "code": source["code"],
            "scope": source["scope"],
            "employeeRef": source["employeeRef"],
            "categoryCode": source["categoryCode"],
            "impact": source["impact"],
            "affectedSections": sorted(sections),
            "summary": limitation_summary(source),
            "evidenceRefs": list(source["evidenceRefs"]),
        })
    normalized["dataLimitations"] = limitations
    return normalized


def _number(value: object) -> float:
    return float(str(value))


def backend_team_overview(payload: dict) -> dict | None:
    rating_facts = [
        fact
        for employee in payload["facts"]["employees"]
        for fact in employee["facts"]
        if fact["metricCode"] == "RATING_STRUCTURE_SCORE"
    ]
    if (
        len(rating_facts) >= 2
        and len({_number(fact["value"]) for fact in rating_facts}) == 1
    ):
        return {
            "text": (
                "Результаты сотрудников по доступной компетенции равны."
            ),
            "evidenceRefs": [
                fact["evidenceRef"] for fact in rating_facts
            ],
        }

    eligible = next((
        fact
        for fact in payload["facts"]["team"]
        if fact["metricCode"] == "RATING_ELIGIBLE_COUNT"
    ), None)
    if eligible is None:
        return None
    comparable = _number(eligible["value"]) >= 2
    return {
        "text": (
            "Командные данные позволяют сопоставить сотрудников."
            if comparable
            else "Сопоставление сотрудников ограничено недостаточной "
            "командной базой."
        ),
        "evidenceRefs": [eligible["evidenceRef"]],
    }


def backend_store_summary(payload: dict) -> dict | None:
    if store_candidates(payload):
        return None
    facts = payload["facts"]["store"]

    narrative = None
    for fact in facts:
        if fact["metricCode"] == "PLAN_PROJECTED_COMPLETION_PERCENT":
            value = _number(fact["value"])
            if value == 100:
                text = "План выполнен на целевом уровне."
            elif value > 100:
                text = "Выполнение плана выше целевого уровня."
            else:
                text = "Выполнение плана ниже целевого уровня."
            narrative = (text, fact["evidenceRef"])
            break

    if narrative is None:
        for fact in facts:
            small_denominator = (
                fact["metricCode"].startswith("DENOMINATOR_")
                and _number(fact["value"]) < 5
            )
            if (
                ".ATTACH:" in fact["evidenceRef"]
                and (
                    fact["sufficiency"] != "SUFFICIENT"
                    or small_denominator
                )
            ):
                narrative = (
                    "База продаж недостаточна для надёжной оценки "
                    "частоты дополнительных продаж.",
                    fact["evidenceRef"],
                )
                break

    if narrative is None:
        for fact in facts:
            comparison = fact.get("comparison")
            if (
                ".CATEGORY:" in fact["evidenceRef"]
                and comparison is not None
                and comparison.get("previousValue") == 0
                and _number(fact["value"]) > 0
            ):
                narrative = (
                    "Выручка категории появилась после нулевого "
                    "значения прошлого периода.",
                    fact["evidenceRef"],
                )
                break

    if narrative is None:
        for fact in facts:
            comparison = fact.get("comparison")
            if (
                fact["metricCode"] == "NET_REVENUE"
                and comparison is not None
                and comparison.get("absoluteDelta") == 0
            ):
                narrative = (
                    "Выручка магазина существенно не изменилась "
                    "относительно прошлого периода.",
                    fact["evidenceRef"],
                )
                break

    if narrative is None and facts:
        narrative = (
            "По магазину нет отдельного существенного изменения за период.",
            facts[0]["evidenceRef"],
        )
    if narrative is None:
        return None
    return {
        "scope": "STORE",
        "employeeRef": None,
        "section": "RESULT",
        "categoryCode": None,
        "text": narrative[0],
        "evidenceRefs": [narrative[1]],
    }


def limitation_summary(limitation: dict) -> str:
    if limitation["code"] == "COST_DATA_INCOMPLETE":
        return (
            "Валовая прибыль и маржинальность недоступны из-за "
            "неполных данных о себестоимости."
        )
    if limitation["code"] == "CLASSIFICATION_QUALITY_LIMITED":
        return (
            "Неполная классификация снижает уверенность в выводах "
            "по категориям и дополнительным продажам."
        )
    if limitation["impact"] == "UNAVAILABLE":
        return "Часть данных недоступна для подтверждённого вывода."
    return "Качество данных снижает уверенность в части выводов."


def candidate_relationship(candidate: dict) -> dict | None:
    if candidate["theme"] not in RELATIONSHIP_TYPES:
        return None

    return {
        "type": candidate["theme"],
        "competencyCode": candidate["competencyCode"],
        "sourceEmployeeRefs": (
            [candidate["employeeRef"]] if candidate["employeeRef"] else []
        ),
        "targetEmployeeRefs": candidate["targetEmployeeRefs"],
    }


def backend_employee_candidates(payload: dict) -> dict[str, dict]:
    result = {}
    candidates = payload["facts"]["candidateSignals"]
    for employee in payload["facts"]["employees"]:
        if (
            employee["analysisStatus"] == "INSUFFICIENT"
            or not employee["facts"]
        ):
            continue
        employee_evidence = {
            fact["evidenceRef"] for fact in employee["facts"]
        }
        candidate = next((
            value for value in candidates
            if value["employeeRef"] == employee["employeeRef"]
            and value["theme"] not in RELATIONSHIP_TYPES
            and set(value["evidenceRefs"]) <= employee_evidence
        ), None)
        if candidate is not None:
            result[employee["employeeRef"]] = candidate
    return result


def backend_employee_candidate_refs(output: dict, payload: dict) -> set[str]:
    result = set()
    summaries = output.get("summaryBlocks", [])
    for employee_ref, candidate in backend_employee_candidates(payload).items():
        _, expected_text = candidate_narrative(candidate, payload)
        expected_evidence = candidate_evidence_refs(candidate, payload)
        if any(
            isinstance(summary, dict)
            and summary.get("scope") == "EMPLOYEE"
            and summary.get("employeeRef") == employee_ref
            and summary.get("section") == "HEADLINE"
            and summary.get("text") == expected_text
            and summary.get("evidenceRefs") == expected_evidence
            for summary in summaries
        ):
            result.add(candidate["candidateRef"])
    return result


def backend_employee_headlines(payload: dict) -> dict:
    employees = {
        employee["employeeRef"]: employee
        for employee in payload["facts"]["employees"]
    }
    candidates = backend_employee_candidates(payload)
    result = {}
    for employee_ref in payload["manifest"]["employeeRefs"]:
        employee = employees.get(employee_ref)
        if not employee or not employee["facts"]:
            continue
        facts = employee["facts"]
        employee_evidence = {fact["evidenceRef"] for fact in facts}
        candidate = candidates.get(employee_ref)
        if employee["analysisStatus"] == "INSUFFICIENT":
            evidence = next((
                fact["evidenceRef"] for fact in facts
                if fact["metricCode"] == "WORKLOAD_STATUS"
            ), facts[0]["evidenceRef"])
            result[employee_ref] = {
                "text": (
                    "Данных недостаточно для персонального анализа "
                    "сотрудника."
                ),
                "evidenceRefs": [evidence],
            }
        elif candidate is not None:
            _, summary = candidate_narrative(candidate, payload)
            result[employee_ref] = {
                "text": summary,
                "evidenceRefs": candidate_evidence_refs(candidate, payload),
            }
        else:
            if employee["analysisStatus"] == "LIMITED":
                fact = next((
                    value for value in facts
                    if value["metricCode"] != "WORKLOAD_STATUS"
                ), facts[0])
                text = (
                    "По сотруднику доступен только ограниченный текущий "
                    "результат."
                )
            else:
                fact = next((
                    value for value in facts
                    if value["metricCode"] == "NET_REVENUE"
                    and value.get("comparison") is not None
                    and value["comparison"].get("absoluteDelta") == 0
                ), facts[0])
                text = (
                    "Выручка сотрудника существенно не изменилась "
                    "относительно прошлого периода."
                    if fact["metricCode"] == "NET_REVENUE"
                    and fact.get("comparison") is not None
                    and fact["comparison"].get("absoluteDelta") == 0
                    else "По сотруднику нет отдельного существенного "
                    "изменения за период."
                )
            result[employee_ref] = {
                "text": text,
                "evidenceRefs": [fact["evidenceRef"]],
            }
    return result


def relationship_summary(theme: str) -> str:
    summaries = {
        "COMPETENCY_LEADER": (
            "В команде подтверждён лидер по соответствующей компетенции."
        ),
        "MOST_IMPROVED": (
            "Подтверждена наиболее заметная положительная динамика "
            "среди сопоставимых сотрудников."
        ),
        "LEARNING_OPPORTUNITY": (
            "Подтверждена возможность обмена практикой по "
            "соответствующей компетенции."
        ),
    }
    return summaries[theme]


def all_input_facts(payload: dict) -> list[dict]:
    return [
        *payload["facts"]["store"],
        *(
            fact
            for employee in payload["facts"]["employees"]
            for fact in employee["facts"]
        ),
    ]


def direction_matches(kind: str, fact: dict) -> bool:
    comparison = fact.get("comparison")
    if comparison is None or comparison.get("absoluteDelta") is None:
        return False
    delta = _number(comparison["absoluteDelta"])
    return kind == "RISK" and delta < 0 or (
        kind == "OPPORTUNITY" and delta > 0
    )


def candidate_evidence_refs(candidate: dict, payload: dict) -> list[str]:
    result = list(candidate["evidenceRefs"])
    if candidate["theme"] == "PROFITABILITY":
        available = {
            evidence["evidenceRef"]
            for evidence in payload["manifest"]["evidence"]
            if evidence["available"]
        }
        for fact in payload["facts"]["store"]:
            if (
                fact["metricCode"] == "MARGIN_PERCENT"
                and direction_matches(candidate["kind"], fact)
                and fact["evidenceRef"] in available
                and fact["evidenceRef"] not in result
            ):
                result.append(fact["evidenceRef"])
    return result


def candidate_narrative(
    candidate: dict,
    payload: dict | None = None,
) -> tuple[str, str]:
    if payload is not None and candidate["theme"] == "PLAN":
        period_end = date.fromisoformat(payload["snapshot"]["period"]["end"])
        completed_month = (period_end + timedelta(days=1)).month != (
            period_end.month
        )
        if candidate["kind"] == "RISK" and completed_month:
            return (
                "Выполнение плана",
                "Завершившийся период закрыт существенно ниже целевого "
                "уровня выполнения плана.",
            )

    if payload is not None and candidate["theme"] == "REVENUE_DYNAMICS":
        facts = {
            fact["evidenceRef"]: fact for fact in all_input_facts(payload)
        }
        candidate_facts = [
            facts[reference]
            for reference in candidate["evidenceRefs"]
            if reference in facts
        ]
        zero_after_sales = any(
            fact["metricCode"] == "NET_REVENUE"
            and _number(fact["value"]) == 0
            and fact.get("comparison") is not None
            and fact["comparison"].get("previousValue") is not None
            and _number(fact["comparison"]["previousValue"]) > 0
            for fact in candidate_facts
        )
        if candidate["kind"] == "RISK" and zero_after_sales:
            return (
                "Динамика чистой выручки",
                "Чистая выручка равна нулю после ненулевого "
                "значения прошлого периода.",
            )
        narratives = {
            "RISK": (
                "Чистая выручка (продажи за вычетом возвратов) "
                "существенно снизилась относительно прошлого периода."
            ),
            "OPPORTUNITY": (
                "Чистая выручка (продажи за вычетом возвратов) "
                "существенно выросла относительно прошлого периода."
            ),
            "OBSERVATION": (
                "Динамика чистой выручки: зафиксировано существенное "
                "изменение."
            ),
        }
        return "Динамика чистой выручки", narratives[candidate["kind"]]

    if payload is not None and candidate["theme"] == "PROFITABILITY":
        margin_changed = any(
            fact["metricCode"] == "MARGIN_PERCENT"
            and direction_matches(candidate["kind"], fact)
            for fact in payload["facts"]["store"]
        )
        if margin_changed:
            narratives = {
                "RISK": (
                    "Валовая прибыль и маржинальность существенно снизились "
                    "относительно прошлого периода."
                ),
                "OPPORTUNITY": (
                    "Валовая прибыль и маржинальность существенно выросли "
                    "относительно прошлого периода."
                ),
                "OBSERVATION": (
                    "Динамика прибыльности: зафиксировано существенное "
                    "изменение."
                ),
            }
            return "Динамика прибыльности", narratives[candidate["kind"]]

    label = None
    if payload is not None and candidate.get("categoryCode") is not None:
        label = payload["manifest"]["categoryLabels"].get(
            candidate["categoryCode"]
        )
    if label:
        quoted = f"«{label}»"
        contextual = {
            "CATEGORY_MIX": (
                f"Динамика категории {quoted}",
                f"Выручка и доля категории {quoted} существенно снизились.",
                f"Выручка и доля категории {quoted} существенно выросли.",
            ),
            "ADDITIONAL_SALES": (
                f"Дополнительные продажи категории {quoted}",
                f"Выручка категории {quoted} существенно снизилась.",
                f"Выручка категории {quoted} существенно выросла.",
            ),
            "ATTACH_RATE": (
                f"Частота дополнительных продаж категории {quoted}",
                f"Частота дополнительных продаж категории {quoted} "
                "существенно снизилась при достаточной базе.",
                f"Частота дополнительных продаж категории {quoted} "
                "существенно выросла при достаточной базе.",
            ),
        }.get(candidate["theme"])
        if contextual is not None:
            title, risk, opportunity = contextual
            summaries = {
                "RISK": risk,
                "OPPORTUNITY": opportunity,
                "OBSERVATION": (
                    f"{title}: зафиксировано существенное изменение."
                ),
            }
            return title, summaries[candidate["kind"]]

    narratives = {
        "PLAN": (
            "Выполнение плана",
            "Выполнение плана существенно ниже целевого уровня.",
            "Выполнение плана выше целевого уровня.",
        ),
        "REVENUE_DYNAMICS": (
            "Динамика выручки",
            "Выручка существенно снизилась относительно прошлого периода.",
            "Выручка существенно выросла относительно прошлого периода.",
        ),
        "PROFITABILITY": (
            "Динамика валовой прибыли",
            "Валовая прибыль существенно снизилась относительно прошлого периода.",
            "Валовая прибыль существенно выросла относительно прошлого периода.",
        ),
        "CATEGORY_MIX": (
            "Динамика категории",
            "Выручка и доля выбранной категории существенно снизились.",
            "Выручка и доля выбранной категории существенно выросли.",
        ),
        "ADDITIONAL_SALES": (
            "Дополнительные продажи",
            "Выручка дополнительных продаж существенно снизилась.",
            "Выручка дополнительных продаж существенно выросла.",
        ),
        "ATTACH_RATE": (
            "Прикрепление дополнительных позиций",
            "Частота дополнительных продаж существенно снизилась при достаточной базе.",
            "Частота дополнительных продаж существенно выросла при достаточной базе.",
        ),
        "EMPLOYEE_PERFORMANCE": (
            "Динамика результата сотрудника",
            "Результат сотрудника существенно снизился относительно его прошлого периода.",
            "Результат сотрудника существенно улучшился относительно его прошлого периода.",
        ),
    }
    narrative = narratives.get(candidate["theme"])
    if narrative is not None:
        title, risk, opportunity = narrative
        summaries = {
            "RISK": risk,
            "OPPORTUNITY": opportunity,
            "OBSERVATION": (
                f"{title}: зафиксировано существенное изменение."
            ),
        }
        return title, summaries[candidate["kind"]]

    titles = {
        "FINANCIAL_RESULT": "Финансовый результат",
        "RETURNS": "Динамика возвратов",
        "TIME_EFFICIENCY": "Эффективность рабочего времени",
        "TEAM_PERFORMANCE": "Командный результат",
        "SALES_QUALITY": "Качество продаж",
        "DATA_QUALITY": "Качество данных",
        "OTHER": "Подтверждённый бизнес-сигнал",
    }
    title = titles.get(candidate["theme"], titles["OTHER"])
    summaries = {
        "RISK": f"{title} требует внимания.",
        "OPPORTUNITY": f"{title}: подтверждён положительный сигнал.",
        "OBSERVATION": f"{title}: подтверждено изменение.",
    }
    return title, summaries[candidate["kind"]]


def production_metric_code(evidence_ref: str) -> str | None:
    exact = {
        "TEAM.RATING.ELIGIBLE_COUNT": "RATING_ELIGIBLE_COUNT",
    }
    if evidence_ref in exact:
        return exact[evidence_ref]
    patterns = (
        (r"STORE\.PLAN:(?:REVENUE|ACCESSORY|SERVICE|ADDITIONAL)\.([A-Z_]+)",
         lambda match: "PLAN_" + match.group(1)),
        (r"STORE\.(?:CATEGORY|GROUP):[A-Z0-9_-]+\.([A-Z_]+)\.CURRENT",
         lambda match: match.group(1)),
        (r"STORE\.ATTACH:[A-Z0-9_-]+\.([A-Z_]+)\.CURRENT",
         lambda match: match.group(1)),
        (r"STORE\.([A-Z_]+)\.CURRENT",
         lambda match: match.group(1)),
        (r"EMP:E[0-9]{2,4}\.(?:CATEGORY|GROUP):[A-Z0-9_-]+\."
         r"([A-Z_]+)\.CURRENT",
         lambda match: match.group(1)),
        (r"EMP:E[0-9]{2,4}\.ATTACH:[A-Z0-9_-]+\.([A-Z_]+)\.CURRENT",
         lambda match: match.group(1)),
        (r"EMP:E[0-9]{2,4}\.RATING\.([A-Z_]+)\.CURRENT",
         lambda match: "RATING_" + match.group(1)),
        (r"EMP:E[0-9]{2,4}\.WORKLOAD\.([A-Z_]+)\.CURRENT",
         lambda match: match.group(1)),
        (r"EMP:E[0-9]{2,4}\.WORKLOAD\.STATUS",
         lambda match: "WORKLOAD_STATUS"),
        (r"EMP:E[0-9]{2,4}\.SALES\.([A-Z_]+)\.CURRENT",
         lambda match: match.group(1)),
        (r"EMP:E[0-9]{2,4}\.SALES_STRUCTURE\.STATUS",
         lambda match: "SALES_STRUCTURE_STATUS"),
        (r"EMP:E[0-9]{2,4}\.([A-Z_]+)\.CURRENT",
         lambda match: match.group(1)),
        (r"TEAM\.(?:CATEGORY|GROUP):[A-Z0-9_-]+\.([A-Z_]+)\."
         r"(Q1|MEDIAN|Q3)",
         lambda match: "TEAM_" + match.group(1) + "_" + match.group(2)),
        (r"TEAM\.METRIC:([A-Z_]+)\.(Q1|MEDIAN|Q3)",
         lambda match: "TEAM_" + match.group(1) + "_" + match.group(2)),
    )
    for pattern, metric in patterns:
        match = re.fullmatch(pattern, evidence_ref)
        if match:
            return metric(match)
    return None


def input_semantic_failures(case_id: str, payload: dict, case: dict) -> list[str]:
    failures: list[str] = []
    manifest = payload["manifest"]
    facts = payload["facts"]
    snapshot = payload["snapshot"]
    period_start = date.fromisoformat(snapshot["period"]["start"])
    period_end = date.fromisoformat(snapshot["period"]["end"])
    comparison_start = date.fromisoformat(
        snapshot["comparisonPeriod"]["start"]
    )
    comparison_end = date.fromisoformat(snapshot["comparisonPeriod"]["end"])
    if (
        period_start.weekday() != 0
        or period_end != period_start + timedelta(days=6)
    ):
        failures.append(f"{case_id}: period must be a Monday-Sunday week")
    if (
        comparison_start.weekday() != 0
        or comparison_end != comparison_start + timedelta(days=6)
    ):
        failures.append(
            f"{case_id}: comparisonPeriod must be a Monday-Sunday week"
        )
    if comparison_end != period_start - timedelta(days=1):
        failures.append(f"{case_id}: comparisonPeriod must immediately precede period")
    known_evidence = {
        entry["evidenceRef"]: entry for entry in manifest["evidence"]
    }
    available_evidence = {
        reference for reference, entry in known_evidence.items()
        if entry["available"]
    }
    employee_refs = [
        employee["employeeRef"] for employee in facts["employees"]
    ]
    all_facts = list(facts["store"]) + list(facts["team"])
    for employee in facts["employees"]:
        all_facts.extend(employee["facts"])
    for fact in all_facts:
        expected_metric = production_metric_code(fact["evidenceRef"])
        if expected_metric != fact["metricCode"]:
            failures.append(
                f"{case_id}: fact {fact['evidenceRef']} does not match "
                f"production metric {fact['metricCode']}"
            )
    if len(employee_refs) != len(set(employee_refs)):
        failures.append(f"{case_id}: duplicate employeeRef")
    if employee_refs != manifest["employeeRefs"]:
        failures.append(
            f"{case_id}: manifest employeeRefs do not match employee facts"
        )
    candidate_refs = [candidate["candidateRef"] for candidate in facts["candidateSignals"]]
    invalid_candidate_refs = [
        reference for reference in candidate_refs
        if re.fullmatch(r"C[0-9]{3}", reference) is None
    ]
    if invalid_candidate_refs:
        failures.append(f"{case_id}: non-production candidateRef format")
    if len(candidate_refs) != len(set(candidate_refs)):
        failures.append(f"{case_id}: duplicate candidateRef")
    if set(candidate_refs) != set(manifest["candidateRefs"]):
        failures.append(f"{case_id}: manifest candidateRefs do not match candidates")
    for candidate in facts["candidateSignals"]:
        if (
            candidate["employeeRef"] is not None
            and candidate["employeeRef"] not in employee_refs
        ):
            failures.append(
                f"{case_id}: candidate {candidate['candidateRef']} uses "
                f"unknown employee {candidate['employeeRef']}"
            )
        unknown_targets = set(candidate["targetEmployeeRefs"]) - set(employee_refs)
        if unknown_targets:
            failures.append(
                f"{case_id}: candidate {candidate['candidateRef']} uses "
                f"unknown targets: {sorted(unknown_targets)}"
            )
        relationship = candidate_relationship(candidate)
        if relationship is not None:
            valid_shape = (
                candidate["kind"] == "OPPORTUNITY"
                and len(relationship["sourceEmployeeRefs"]) == 1
                and (
                    relationship["type"] == "COMPETENCY_LEADER"
                    and relationship["competencyCode"] is not None
                    and not relationship["targetEmployeeRefs"]
                    or relationship["type"] == "MOST_IMPROVED"
                    and relationship["competencyCode"] is None
                    and not relationship["targetEmployeeRefs"]
                    or relationship["type"] == "LEARNING_OPPORTUNITY"
                    and relationship["competencyCode"] is not None
                    and bool(relationship["targetEmployeeRefs"])
                )
            )
            if not valid_shape:
                failures.append(
                    f"{case_id}: relationship candidate "
                    f"{candidate['candidateRef']} has invalid shape"
                )
        unknown = set(candidate["evidenceRefs"]) - available_evidence
        if unknown:
            failures.append(
                f"{case_id}: candidate {candidate['candidateRef']} uses unavailable evidence: "
                f"{sorted(unknown)}"
            )
    for limitation in manifest["limitations"]:
        unknown = set(limitation["evidenceRefs"]) - set(known_evidence)
        if unknown:
            failures.append(
                f"{case_id}: limitation {limitation['code']} uses unknown evidence: "
                f"{sorted(unknown)}"
            )
    expectations = case["expectations"]
    required = set(expectations.get("requiredCandidateRefs", []))
    forbidden = set(expectations.get("forbiddenCandidateRefs", []))
    if required - set(candidate_refs):
        failures.append(
            f"{case_id}: requiredCandidateRefs are absent from input: "
            f"{sorted(required - set(candidate_refs))}"
        )
    if forbidden & set(candidate_refs):
        failures.append(
            f"{case_id}: forbiddenCandidateRefs are present in input: "
            f"{sorted(forbidden & set(candidate_refs))}"
        )
    input_relationships = [
        relationship
        for candidate in facts["candidateSignals"]
        if (relationship := candidate_relationship(candidate)) is not None
    ]
    for selector in expectations.get("requiredTeamRelationships", []):
        if not any(
            relationship_selector_matches(relationship, selector)
            for relationship in input_relationships
        ):
            failures.append(
                f"{case_id}: requiredTeamRelationship is absent from input: "
                f"{selector}"
            )
    if not any(
        expectations.get(field)
        for field in ("requiredFindings", "acceptableFindings", "forbiddenFindings")
    ):
        failures.append(f"{case_id}: semantic expectations are empty")
    return failures


def validate_dataset(
    repository: Path,
    manifest_path: Path,
    dataset: dict,
) -> tuple[list[str], dict[str, dict]]:
    failures: list[str] = []
    schema_path = repository_path(
        repository,
        dataset.get(
            "datasetSchema",
            "scripts/llm-eval/dataset-v2.schema.json",
        ),
    )
    dataset_validator = Draft202012Validator(
        load_json(schema_path),
        format_checker=FormatChecker(),
    )
    failures.extend(schema_failures(dataset_validator, dataset, "dataset"))

    if failures:
        return failures, {}

    input_schema_path = repository_path(repository, dataset["inputSchema"])
    input_validator = Draft202012Validator(
        load_json(input_schema_path),
        format_checker=FormatChecker(),
    )
    output_schemas = {
        dataset["outputSchema"],
        *(
            configuration.get("outputSchema", dataset["outputSchema"])
            for configuration in dataset["configurations"]
        ),
    }
    for output_schema in sorted(output_schemas):
        output_schema_path = repository_path(repository, output_schema)
        Draft202012Validator.check_schema(load_json(output_schema_path))

    for pattern in dataset.get("globalForbiddenPatterns", []):
        try:
            re.compile(pattern)
        except re.error as exception:
            failures.append(
                f"dataset: invalid global forbidden pattern {pattern!r}: {exception}"
            )
    action_policy = dataset.get("actionQuality", {})
    for pattern in action_policy.get("specificityPatterns", []):
        try:
            re.compile(pattern)
        except re.error as exception:
            failures.append(
                f"dataset: invalid action specificity pattern "
                f"{pattern!r}: {exception}"
            )
    for pattern in action_policy.get("forbiddenBoilerplatePatterns", []):
        try:
            re.compile(pattern)
        except re.error as exception:
            failures.append(
                f"dataset: invalid action boilerplate pattern "
                f"{pattern!r}: {exception}"
            )
    narrative_policy = dataset.get("narrativeQuality", {})
    for pattern in narrative_policy.get("insightDirectivePatterns", []):
        try:
            re.compile(pattern)
        except re.error as exception:
            failures.append(
                f"dataset: invalid insight directive pattern "
                f"{pattern!r}: {exception}"
            )
    for pattern in narrative_policy.get("unsupportedCausePatterns", []):
        try:
            re.compile(pattern)
        except re.error as exception:
            failures.append(
                f"dataset: invalid unsupported-cause pattern "
                f"{pattern!r}: {exception}"
            )
    if action_policy.get(
        "nonSpecificNearDuplicateSimilarity", 0
    ) > action_policy.get("nearDuplicateSimilarity", 1):
        failures.append(
            "dataset: non-specific near-duplicate threshold must not "
            "exceed the strong threshold"
        )

    configurations = dataset.get("configurations", [])
    configuration_ids = [configuration.get("id") for configuration in configurations]
    if len(configuration_ids) != len(set(configuration_ids)):
        failures.append("dataset: configuration ids must be unique")
    cases = dataset.get("cases", [])
    case_ids = [case.get("id") for case in cases]
    if len(case_ids) != len(set(case_ids)):
        failures.append("dataset: case ids must be unique")

    coverage = dataset.get("coverage", {})
    minimum_cases = coverage.get("minimumCases", 1)
    if len(cases) < minimum_cases:
        failures.append(
            f"dataset: expected at least {minimum_cases} cases, got {len(cases)}"
        )
    present_tags = {
        tag for case in cases for tag in case.get("tags", [])
    }
    missing_tags = set(coverage.get("requiredTags", [])) - present_tags
    if missing_tags:
        failures.append(
            f"dataset: missing required coverage tags: {sorted(missing_tags)}"
        )

    inputs: dict[str, dict] = {}
    for case in cases:
        case_id = case.get("id", "unnamed")
        for pattern in case.get("expectations", {}).get("forbiddenPatterns", []):
            try:
                re.compile(pattern)
            except re.error as exception:
                failures.append(
                    f"{case_id}: invalid forbidden pattern "
                    f"{pattern!r}: {exception}"
                )
        try:
            payload = build_input(dataset, case)
        except (KeyError, TypeError, ValueError) as exception:
            failures.append(f"{case_id}: cannot build input: {exception}")
            continue
        inputs[case_id] = payload
        failures.extend(schema_failures(input_validator, payload, f"{case_id} input"))
        failures.extend(input_semantic_failures(case_id, payload, case))

    return failures, inputs


def candidate_failures(case_id: str, output: dict, payload: dict) -> list[str]:
    failures: list[str] = []
    candidates = {
        candidate["candidateRef"]: candidate
        for candidate in payload["facts"]["candidateSignals"]
    }
    candidate_items = [
        (f"insight[{index}]", insight)
        for index, insight in enumerate(output.get("insights", []))
        if isinstance(insight, dict)
    ]
    if isinstance(output.get("primarySignal"), dict):
        candidate_items.insert(0, ("primarySignal", output["primarySignal"]))
    for item_label, item in candidate_items:
        reference = item.get("candidateRef")
        if reference is None:
            continue
        candidate = candidates.get(reference)
        if candidate is None:
            failures.append(
                f"{case_id}: {item_label} references unknown candidate {reference}"
            )
            continue
        expected = {
            "kind": candidate["kind"],
            "theme": candidate["theme"],
            "employeeRef": candidate["employeeRef"],
            "categoryCode": candidate["categoryCode"],
        }
        mismatched = {
            field: (expected_value, item.get(field))
            for field, expected_value in expected.items()
            if item.get(field) != expected_value
        }
        if mismatched:
            failures.append(
                f"{case_id}: {item_label} mismatches candidate {reference}: "
                f"{mismatched}"
            )
        missing_evidence = set(candidate["evidenceRefs"]) - set(
            item.get("evidenceRefs", [])
        )
        if missing_evidence:
            failures.append(
                f"{case_id}: {item_label} omits candidate evidence: "
                f"{sorted(missing_evidence)}"
            )
    return failures

def relationship_failures(case_id: str, output: dict, payload: dict) -> list[str]:
    failures: list[str] = []
    candidates = [
        candidate
        for candidate in payload["facts"]["candidateSignals"]
        if candidate["theme"] in RELATIONSHIP_TYPES
    ]
    for index, relationship in enumerate(output.get("teamRelationships", [])):
        if not isinstance(relationship, dict):
            continue
        matching = [
            candidate
            for candidate in candidates
            if relationship.get("type") == candidate["theme"]
            and relationship.get("competencyCode") == candidate["competencyCode"]
            and set(relationship.get("sourceEmployeeRefs", []))
            == set([candidate["employeeRef"]] if candidate["employeeRef"] else [])
            and set(relationship.get("targetEmployeeRefs", []))
            == set(candidate["targetEmployeeRefs"])
            and set(candidate["evidenceRefs"]).issubset(
                relationship.get("evidenceRefs", [])
            )
        ]
        if not matching:
            failures.append(
                f"{case_id}: teamRelationship[{index}] is not backed by "
                "an exact input candidate"
            )
    return failures


def limitation_failures(case_id: str, output: dict, payload: dict) -> list[str]:
    failures: list[str] = []
    expected = {
        limitation["code"]: limitation
        for limitation in payload["manifest"]["limitations"]
    }
    actual = [
        limitation
        for limitation in output.get("dataLimitations", [])
        if isinstance(limitation, dict)
    ]
    actual_codes = {
        limitation.get("code")
        for limitation in actual
        if limitation.get("code")
    }
    if len(actual_codes) != len(actual):
        failures.append(
            f"{case_id}: duplicate or missing data limitation code"
        )
    missing = set(expected) - actual_codes
    if missing:
        failures.append(
            f"{case_id}: required data limitations are absent: {sorted(missing)}"
        )
    for index, limitation in enumerate(actual):
        source = expected.get(limitation.get("code"))
        if source is None:
            failures.append(
                f"{case_id}: dataLimitation[{index}] is not declared by input"
            )
            continue
        scalar_fields = ("scope", "employeeRef", "categoryCode", "impact")
        mismatched = {
            field: (source[field], limitation.get(field))
            for field in scalar_fields
            if limitation.get(field) != source[field]
        }
        if mismatched:
            failures.append(
                f"{case_id}: dataLimitation[{index}] mismatches input: "
                f"{mismatched}"
            )
        for field in ("affectedSections", "evidenceRefs"):
            source_values = source[field]
            if field == "affectedSections":
                source_values = {
                    CANONICAL_LIMITATION_SECTIONS.get(value, value)
                    for value in source_values
                }
            if set(limitation.get(field, [])) != set(source_values):
                failures.append(
                    f"{case_id}: dataLimitation[{index}] has different {field}"
                )
    return failures


def response_metrics(
    output: dict,
    case: dict,
    dataset: dict,
    payload: dict | None = None,
    configuration: dict | None = None,
) -> dict:
    action_result = action_quality(dataset, output)
    narrative_result = narrative_quality(dataset, output)
    insight_candidate_refs = {
        insight.get("candidateRef")
        for insight in output.get("insights", [])
        if isinstance(insight, dict) and insight.get("candidateRef")
    }
    primary_signal = output.get("primarySignal")
    primary_candidate_ref = (
        primary_signal.get("candidateRef")
        if isinstance(primary_signal, dict) else None
    )
    candidate_refs = set(insight_candidate_refs)
    if primary_candidate_ref:
        candidate_refs.add(primary_candidate_ref)
    if (
        configuration is not None
        and configuration["promptVersion"]
        == "weekly-interpretation-v19"
    ):
        candidate_refs.update(
            backend_employee_candidate_refs(output, payload)
        )
    duplicate_count = sum(
        len(paths) - 1
        for paths in duplicate_narrative_groups(output).values()
    )
    required = set(case["expectations"].get("requiredCandidateRefs", []))
    return {
        "summaryBlocks": len(output.get("summaryBlocks", [])),
        "workloadBlocks": sum(
            summary.get("section") == "WORKLOAD"
            for summary in output.get("summaryBlocks", [])
            if isinstance(summary, dict)
        ),
        "insights": len(output.get("insights", [])),
        "candidateBackedInsights": len(insight_candidate_refs),
        "primarySignals": int(primary_candidate_ref is not None),
        "candidateBackedSignals": len(candidate_refs),
        "actions": len(output.get("actions", [])),
        "nonSpecificActions": len(action_result["nonSpecificIndexes"]),
        "nearDuplicateActions": len(action_result["nearDuplicatePairs"]),
        "nearDuplicateNarratives": (
            len(narrative_result["nearDuplicateHeadlineInsightPairs"])
            + len(narrative_result["nearDuplicatePrimaryInsightPairs"])
            + len(
                narrative_result["nearDuplicatePrimaryTeamOverviewPairs"]
            )
        ),
        "nearDuplicatePrimaryTeamOverviews": len(
            narrative_result["nearDuplicatePrimaryTeamOverviewPairs"]
        ),
        "directiveInsights": len(
            narrative_result["directiveInsightIndexes"]
        ),
        "unsupportedCauseNarratives": len(
            narrative_result["unsupportedCauseItems"]
        ),
        "teamRelationships": len(output.get("teamRelationships", [])),
        "duplicateNarratives": duplicate_count,
        "requiredCandidateCoverage": (
            len(required & candidate_refs) / len(required) if required else 1.0
        ),
    }


def validate_response(
    validator: Draft202012Validator,
    dataset: dict,
    case: dict,
    payload: dict,
    output: object,
    label: str,
) -> tuple[list[str], dict]:
    case_id = case["id"]
    prefix = f"{case_id}/{label}"
    configuration = next(
        (
            value
            for value in dataset["configurations"]
            if value["id"] == label
        ),
        None,
    )
    content_schema_version = (
        configuration["contentSchemaVersion"]
        if configuration is not None else 2
    )
    if not isinstance(output, dict):
        return schema_failures(validator, output, prefix), {}
    failures: list[str] = []
    if (
        configuration is not None
        and configuration["promptVersion"]
        == "weekly-interpretation-v19"
    ):
        failures.extend(
            privacy_reduced_provider_failures(prefix, output, payload)
        )
    output = backend_normalize_response(output, payload)
    failures.extend(schema_failures(validator, output, prefix))

    manifest = payload["manifest"]
    known_evidence = {
        entry["evidenceRef"] for entry in manifest["evidence"]
    }
    available_evidence = {
        entry["evidenceRef"] for entry in manifest["evidence"]
        if entry["available"]
    }
    unknown_analysis = (
        evidence_refs(output, exclude_limitations=True) - available_evidence
    )
    if unknown_analysis:
        failures.append(
            f"{prefix}: analysis references unavailable evidence: "
            f"{sorted(unknown_analysis)}"
        )
    limitation_refs = evidence_refs(output.get("dataLimitations", []))
    if limitation_refs - known_evidence:
        failures.append(
            f"{prefix}: limitations reference unknown evidence: "
            f"{sorted(limitation_refs - known_evidence)}"
        )
    failures.extend(limitation_failures(prefix, output, payload))
    if (
        configuration is not None
        and configuration["promptVersion"] in {
            "weekly-interpretation-v15",
            "weekly-interpretation-v16",
            "weekly-interpretation-v17",
            "weekly-interpretation-v18",
            "weekly-interpretation-v19",
        }
    ):
        failures.extend(
            team_overview_evidence_failures(prefix, output, payload)
        )

    expected_employees = {
        employee["employeeRef"]: employee["analysisStatus"]
        for employee in payload["facts"]["employees"]
    }
    actual_employees = {
        employee.get("employeeRef"): employee.get("analysisStatus")
        for employee in output.get("employees", [])
        if isinstance(employee, dict)
    }
    actual_employee_items = [
        employee for employee in output.get("employees", [])
        if isinstance(employee, dict)
    ]
    descriptors_differ = (
        len(actual_employee_items) != len(expected_employees)
        or actual_employees != expected_employees
    )
    if descriptors_differ:
        failures.append(
            f"{prefix}: employee descriptors differ: expected "
            f"{expected_employees}, got {actual_employees}"
        )

    summaries = [
        summary for summary in output.get("summaryBlocks", [])
        if isinstance(summary, dict)
    ]
    mandatory = [
        {"scope": "TEAM", "employeeRef": None, "section": "TEAM_OVERVIEW"},
        *[
            {
                "scope": "EMPLOYEE",
                "employeeRef": employee_ref,
                "section": "HEADLINE",
            }
            for employee_ref in expected_employees
        ],
    ]
    store_headlines = [
        summary
        for summary in summaries
        if selector_matches(summary, {
            "scope": "STORE",
            "employeeRef": None,
            "section": "HEADLINE",
        })
    ]
    if content_schema_version < 3:
        mandatory.insert(0, {
            "scope": "STORE",
            "employeeRef": None,
            "section": "HEADLINE",
        })
    elif store_headlines:
        failures.append(f"{prefix}: STORE headline is forbidden in schema v3")
    for selector in mandatory + case["expectations"].get("requiredSummarySections", []):
        if not any(selector_matches(summary, selector) for summary in summaries):
            failures.append(f"{prefix}: missing summary section {selector}")
    for selector in case["expectations"].get("forbiddenSummarySections", []):
        if any(selector_matches(summary, selector) for summary in summaries):
            failures.append(f"{prefix}: forbidden summary section {selector}")

    insights = [
        insight for insight in output.get("insights", [])
        if isinstance(insight, dict)
    ]
    insight_candidate_refs = {
        insight.get("candidateRef") for insight in insights
        if isinstance(insight, dict) and insight.get("candidateRef")
    }
    primary_signal = output.get("primarySignal")
    primary_candidate_ref = (
        primary_signal.get("candidateRef")
        if isinstance(primary_signal, dict) else None
    )
    actual_candidate_refs = set(insight_candidate_refs)
    if primary_candidate_ref:
        actual_candidate_refs.add(primary_candidate_ref)
    if (
        configuration is not None
        and configuration["promptVersion"]
        == "weekly-interpretation-v19"
    ):
        actual_candidate_refs.update(
            backend_employee_candidate_refs(output, payload)
        )
    candidate_ref_values = [
        insight.get("candidateRef") for insight in insights
        if insight.get("candidateRef")
    ]
    if primary_candidate_ref:
        candidate_ref_values.append(primary_candidate_ref)
    if len(candidate_ref_values) != len(set(candidate_ref_values)):
        failures.append(f"{prefix}: duplicate candidateRef across primary and insights")
    if content_schema_version >= 3:
        expected_primary_candidates = store_candidates(payload)
        if expected_primary_candidates and not isinstance(primary_signal, dict):
            failures.append(f"{prefix}: primarySignal is required")
        elif not expected_primary_candidates and primary_signal is not None:
            failures.append(f"{prefix}: primarySignal is not allowed")
        elif isinstance(primary_signal, dict):
            expected_ref = expected_primary_candidates[0]["candidateRef"]
            if primary_candidate_ref != expected_ref:
                failures.append(
                    f"{prefix}: primarySignal must use backend-prioritized "
                    f"candidate {expected_ref}"
                )
        for index, insight in enumerate(insights):
            if insight.get("candidateRef") is None:
                failures.append(
                    f"{prefix}: secondary insight[{index}] requires candidateRef"
                )
    required_candidates = set(
        case["expectations"].get("requiredCandidateRefs", [])
    )
    missing_candidates = required_candidates - actual_candidate_refs
    if missing_candidates:
        failures.append(
            f"{prefix}: required candidates are absent: {sorted(missing_candidates)}"
        )
    forbidden_candidates = set(
        case["expectations"].get("forbiddenCandidateRefs", [])
    )
    present_forbidden = forbidden_candidates & actual_candidate_refs
    if present_forbidden:
        failures.append(
            f"{prefix}: forbidden candidates are present: {sorted(present_forbidden)}"
        )
    failures.extend(candidate_failures(prefix, output, payload))
    failures.extend(narrative_dimension_failures(prefix, output, payload))

    expectations = case["expectations"]
    relationships = [
        relationship for relationship in output.get("teamRelationships", [])
        if isinstance(relationship, dict)
    ]
    for selector in expectations.get("requiredTeamRelationships", []):
        if not any(
            relationship_selector_matches(relationship, selector)
            for relationship in relationships
        ):
            failures.append(f"{prefix}: missing team relationship {selector}")
    for selector in expectations.get("forbiddenTeamRelationships", []):
        if any(
            relationship_selector_matches(relationship, selector)
            for relationship in relationships
        ):
            failures.append(f"{prefix}: forbidden team relationship {selector}")
    failures.extend(relationship_failures(prefix, output, payload))
    if len(insights) > expectations.get("maxInsights", 48):
        failures.append(f"{prefix}: too many insights")
    if len(output.get("actions", [])) > expectations.get("maxActions", 24):
        failures.append(f"{prefix}: too many actions")

    if (
        configuration is not None
        and configuration["promptVersion"] in {
            "weekly-interpretation-v12",
            "weekly-interpretation-v13",
            "weekly-interpretation-v14",
            "weekly-interpretation-v15",
            "weekly-interpretation-v16",
            "weekly-interpretation-v17",
            "weekly-interpretation-v18",
            "weekly-interpretation-v19",
        }
    ):
        candidate_action_limit = sum(
            candidate.get("theme") not in RELATIONSHIP_TYPES
            for candidate in payload["facts"]["candidateSignals"]
        )
        if len(output.get("actions", [])) > candidate_action_limit:
            failures.append(
                f"{prefix}: action count exceeds non-relationship "
                f"candidate count ({candidate_action_limit})"
            )

    action_result = action_quality(dataset, output)
    for index in sorted(action_result["nonSpecificIndexes"]):
        failures.append(
            f"{prefix}: action lacks an observable operation "
            f"at $.actions[{index}]"
        )
    for left, right, similarity in action_result["nearDuplicatePairs"]:
        failures.append(
            f"{prefix}: actions are near-duplicates at "
            f"$.actions[{left}] and $.actions[{right}] "
            f"(similarity={similarity:.4f})"
        )

    narrative_result = narrative_quality(dataset, output)
    for summary_index, insight_index, dice, containment in (
        narrative_result["nearDuplicateHeadlineInsightPairs"]
    ):
        failures.append(
            f"{prefix}: STORE headline and insight title are near-duplicates "
            f"at $.summaryBlocks[{summary_index}] and $.insights[{insight_index}] "
            f"(dice={dice:.4f}, containment={containment:.4f})"
        )
    for insight_index, dice, containment in (
        narrative_result["nearDuplicatePrimaryInsightPairs"]
    ):
        failures.append(
            f"{prefix}: primarySignal and insight title are near-duplicates "
            f"at $.primarySignal and $.insights[{insight_index}] "
            f"(dice={dice:.4f}, containment={containment:.4f})"
        )
    for summary_index, dice, containment in (
        narrative_result["nearDuplicatePrimaryTeamOverviewPairs"]
    ):
        failures.append(
            f"{prefix}: primarySignal and TEAM overview are near-duplicates "
            f"at $.primarySignal and $.summaryBlocks[{summary_index}] "
            f"(dice={dice:.4f}, containment={containment:.4f})"
        )
    for index in sorted(narrative_result["directiveInsightIndexes"]):
        failures.append(
            f"{prefix}: insight contains a management directive "
            f"at $.insights[{index}]"
        )
    for collection, index in narrative_result["unsupportedCauseItems"]:
        failures.append(
            f"{prefix}: narrative states an unsupported possible cause "
            f"at $.{collection}[{index}]"
        )

    forbidden_patterns = [
        *dataset.get("globalForbiddenPatterns", []),
        *expectations.get("forbiddenPatterns", []),
    ]
    compiled_patterns = [
        re.compile(pattern, re.IGNORECASE) for pattern in forbidden_patterns
    ]
    narratives = list(narrative_values(output))
    for path, value in narratives:
        for pattern in compiled_patterns:
            if pattern.search(value):
                failures.append(
                    f"{prefix}: forbidden pattern {pattern.pattern!r} at {path}"
                )
        if re.search(r"\d", value):
            failures.append(f"{prefix}: model narrative contains digits at {path}")

    technical_identifiers = {
        *manifest["employeeRefs"],
        *manifest["candidateRefs"],
        *known_evidence,
        *manifest["categoryCodes"],
        *manifest["competencyCodes"],
    }
    for path, value in narratives:
        leaked = sorted(
            identifier for identifier in technical_identifiers
            if identifier and re.search(
                rf"(?<![A-Z0-9:._-]){re.escape(identifier)}(?![A-Z0-9:._-])",
                value, re.IGNORECASE,
            )
        )
        if leaked:
            failures.append(
                f"{prefix}: narrative exposes technical identifiers at {path}: "
                f"{leaked}"
            )

    duplicates = {
        text: len(paths)
        for text, paths in duplicate_narrative_groups(output).items()
    }
    if len(duplicates) > expectations.get("maxDuplicateNarratives", 0):
        failures.append(f"{prefix}: duplicate narratives: {duplicates}")

    employee_facts_by_ref = {
        employee["employeeRef"]: employee
        for employee in payload["facts"]["employees"]
    }
    for index, summary in enumerate(summaries):
        if summary.get("section") != "WORKLOAD":
            continue
        employee = employee_facts_by_ref.get(summary.get("employeeRef"))
        if employee is None:
            continue
        available = set(employee.get("availableSections", []))
        has_workload_evidence = "WORKLOAD" in available or any(
            fact.get("metricCode") == "WORKLOAD_STATUS"
            or ".WORKLOAD." in fact.get("evidenceRef", "")
            for fact in employee.get("facts", [])
        )
        if not has_workload_evidence:
            failures.append(
                f"{prefix}: workload summary lacks workload evidence "
                f"at $.summaryBlocks[{index}]"
            )

    for policy in expectations.get("employeePolicies", []):
        employee_ref = policy["employeeRef"]
        if policy.get("insights") == "FORBIDDEN" and any(
            insight.get("employeeRef") == employee_ref for insight in insights
        ):
            failures.append(f"{prefix}: insights forbidden for {employee_ref}")
        if policy.get("actions") == "FORBIDDEN" and any(
            employee_ref in action.get("targetEmployeeRefs", [])
            for action in output.get("actions", [])
            if isinstance(action, dict)
        ):
            failures.append(f"{prefix}: actions forbidden for {employee_ref}")
        if policy.get("relationships") == "FORBIDDEN" and any(
            employee_ref in relationship.get("sourceEmployeeRefs", [])
            or employee_ref in relationship.get("targetEmployeeRefs", [])
            for relationship in relationships
        ):
            failures.append(f"{prefix}: relationships forbidden for {employee_ref}")
        if policy.get("workload") == "FORBIDDEN" and any(
            summary.get("employeeRef") == employee_ref
            and summary.get("section") == "WORKLOAD"
            for summary in summaries
        ):
            failures.append(f"{prefix}: workload summary forbidden for {employee_ref}")

    return failures, response_metrics(
        output, case, dataset, payload, configuration
    )


def validate_legacy_case(
    root: Path,
    validator: Draft202012Validator,
    case: dict,
) -> list[str]:
    case_id = case.get("id", "unnamed")
    output_path = root / case["output"]
    output = load_json(output_path)
    failures = schema_failures(validator, output, case_id)

    input_path = case.get("input")
    if input_path:
        input_payload = load_json(root / input_path)
        manifest = input_payload.get("manifest", {}) if isinstance(input_payload, dict) else {}
        available = {
            item.get("evidenceRef")
            for item in manifest.get("evidence", [])
            if item.get("available") is True
        }
        unknown = sorted(evidence_refs(output) - available)
        if unknown:
            failures.append(f"{case_id}: output references unknown evidence: {unknown}")

        allowed_employees = set(manifest.get("employeeRefs", []))
        output_employees = {
            value for value in values_for_key(output, "employeeRef")
            if isinstance(value, str)
        }
        unknown_employees = sorted(output_employees - allowed_employees)
        if unknown_employees:
            failures.append(
                f"{case_id}: output references unknown employees: {unknown_employees}"
            )

    assertions = case.get("assertions", {})
    forbidden = [
        re.compile(pattern, re.IGNORECASE)
        for pattern in assertions.get("forbiddenPatterns", [])
    ]
    for field_path, _, value in strings(output):
        for pattern in forbidden:
            if pattern.search(value):
                failures.append(
                    f"{case_id}: forbidden pattern {pattern.pattern!r} at {field_path}"
                )
    for pointer, expected in assertions.get("equals", {}).items():
        try:
            actual = json_pointer(output, pointer)
        except (KeyError, IndexError, TypeError, ValueError):
            failures.append(f"{case_id}: required pointer is absent: {pointer}")
            continue
        if actual != expected:
            failures.append(
                f"{case_id}: {pointer} expected {expected!r}, got {actual!r}"
            )
    return failures


def evaluate_legacy(repository: Path, manifest: dict) -> tuple[list[str], int]:
    schema_path = repository_path(repository, manifest["schema"])
    validator = Draft202012Validator(load_json(schema_path))
    failures: list[str] = []
    cases = manifest.get("cases", [])
    if not cases:
        failures.append("evaluation manifest must contain at least one case")
    for case in cases:
        failures.extend(validate_legacy_case(repository, validator, case))
    return failures, len(cases)


def export_inputs(target: Path, inputs: dict[str, dict]):
    target.mkdir(parents=True, exist_ok=True)
    for case_id, payload in inputs.items():
        path = target / f"{case_id}.json"
        with path.open("w", encoding="utf-8") as output:
            json.dump(payload, output, ensure_ascii=False, indent=2)
            output.write("\n")


def aggregate_metrics(
    metrics: dict[str, list[dict]],
    stats: dict[str, dict],
) -> dict:
    result: dict[str, dict] = {}
    for configuration, configuration_stats in stats.items():
        values = metrics.get(configuration, [])
        totals: dict[str, float] = defaultdict(float)
        for item in values:
            for key, value in item.items():
                totals[key] += value
        count = len(values)
        result[configuration] = {
            **configuration_stats,
            "passRate": (
                round(
                    configuration_stats["passedResponses"]
                    / configuration_stats["evaluatedResponses"],
                    4,
                )
                if configuration_stats["evaluatedResponses"] else None
            ),
            "averages": {
                key: round(value / count, 4)
                for key, value in sorted(totals.items())
            } if count else {},
        }
    return result


def evaluate_dataset_responses(
    repository: Path,
    dataset: dict,
    inputs: dict[str, dict],
    responses_dir: Path | None,
    require_responses: bool,
) -> tuple[list[str], dict]:
    validators = {
        configuration["id"]: Draft202012Validator(
            load_json(repository_path(
                repository,
                configuration.get("outputSchema", dataset["outputSchema"]),
            )),
            format_checker=FormatChecker(),
        )
        for configuration in dataset["configurations"]
    }
    failures: list[str] = []
    metrics: dict[str, list[dict]] = defaultdict(list)
    evaluated = 0
    configurations = dataset["configurations"]
    expected_per_configuration = len(dataset["cases"])
    stats = {
        configuration["id"]: {
            "expectedResponses": expected_per_configuration,
            "evaluatedResponses": 0,
            "passedResponses": 0,
            "missingResponses": 0,
            "violationCount": 0,
        }
        for configuration in configurations
    }
    for case in dataset["cases"]:
        if case["id"] not in inputs:
            continue
        for configuration in configurations:
            relative = Path(case["id"]) / f"{configuration['id']}.json"
            response_path = responses_dir / relative if responses_dir else None
            if response_path is None or not response_path.exists():
                stats[configuration["id"]]["missingResponses"] += 1
                if require_responses:
                    failures.append(
                        f"{case['id']}/{configuration['id']}: missing response "
                        f"{relative}"
                    )
                continue
            evaluated += 1
            stats[configuration["id"]]["evaluatedResponses"] += 1
            try:
                output = load_json(response_path)
            except (OSError, json.JSONDecodeError) as exception:
                failures.append(
                    f"{case['id']}/{configuration['id']}: unreadable response: "
                    f"{exception}"
                )
                stats[configuration["id"]]["violationCount"] += 1
                continue
            response_failures, response_metrics_value = validate_response(
                validators[configuration["id"]],
                dataset,
                case,
                inputs[case["id"]],
                output,
                configuration["id"],
            )
            failures.extend(response_failures)
            stats[configuration["id"]]["violationCount"] += len(
                response_failures
            )
            if not response_failures:
                stats[configuration["id"]]["passedResponses"] += 1
            if response_metrics_value:
                metrics[configuration["id"]].append(response_metrics_value)
    report = {
        "datasetVersion": dataset["version"],
        "caseCount": len(dataset["cases"]),
        "configurationCount": len(configurations),
        "evaluatedResponses": evaluated,
        "automaticMetrics": aggregate_metrics(metrics, stats),
        "passed": not failures,
    }
    return failures, report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--manifest",
        default="scripts/llm-eval/dataset-v2.json",
        help="Path to the versioned evaluation manifest",
    )
    parser.add_argument(
        "--responses-dir",
        help="Directory containing <case-id>/<configuration-id>.json responses",
    )
    parser.add_argument(
        "--require-responses",
        action="store_true",
        help="Fail unless every configured case has every configured response",
    )
    parser.add_argument(
        "--export-inputs",
        help="Write deterministic provider inputs to this directory",
    )
    parser.add_argument(
        "--report",
        help="Write a machine-readable evaluation report",
    )
    arguments = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    manifest_path = repository_path(repository, arguments.manifest)
    manifest = load_json(manifest_path)
    if not isinstance(manifest, dict):
        print("LLM evaluation failed: manifest is not an object.", file=sys.stderr)
        return 1

    if manifest.get("version") == 1:
        failures, case_count = evaluate_legacy(repository, manifest)
        report = {
            "datasetVersion": 1,
            "caseCount": case_count,
            "evaluatedResponses": case_count,
            "passed": not failures,
        }
    elif manifest.get("version") == 2:
        failures, inputs = validate_dataset(repository, manifest_path, manifest)
        if arguments.export_inputs and not failures:
            export_inputs(
                repository_path(repository, arguments.export_inputs),
                inputs,
            )
        if failures:
            report = {
                "datasetVersion": 2,
                "caseCount": len(manifest.get("cases", [])),
                "configurationCount": len(manifest.get("configurations", [])),
                "evaluatedResponses": 0,
                "automaticMetrics": {},
                "passed": False,
            }
        else:
            response_failures, report = evaluate_dataset_responses(
                repository,
                manifest,
                inputs,
                repository_path(repository, arguments.responses_dir)
                if arguments.responses_dir else None,
                arguments.require_responses,
            )
            failures.extend(response_failures)
            report["passed"] = not failures
    else:
        failures = ["unsupported evaluation manifest version"]
        report = {
            "datasetVersion": manifest.get("version"),
            "caseCount": 0,
            "evaluatedResponses": 0,
            "passed": False,
        }

    if arguments.report:
        report_path = repository_path(repository, arguments.report)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        with report_path.open("w", encoding="utf-8") as output:
            json.dump(report, output, ensure_ascii=False, indent=2)
            output.write("\n")

    if failures:
        print(f"LLM evaluation failed: {len(failures)} violation(s).", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    if report["datasetVersion"] == 2:
        print(
            "LLM evaluation dataset passed: "
            f"{report['caseCount']} case(s), "
            f"{report['evaluatedResponses']} response(s)."
        )
    else:
        print(f"LLM evaluation passed: {report['caseCount']} case(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

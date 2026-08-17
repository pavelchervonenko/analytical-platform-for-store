#!/usr/bin/env python3
"""Blinded human review and deterministic decision report for LLM evaluation."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import uuid
from collections import defaultdict
from pathlib import Path

from jsonschema import Draft202012Validator

import evaluate


REVIEW_NAMESPACE = uuid.UUID("f95cbfad-9821-40b6-a063-b22be962a136")
REVIEW_VERSION = 1
DEFAULT_MANIFEST = "scripts/llm-eval/dataset-v2.json"
DEFAULT_RESPONSES = "build/llm-eval/responses"
DEFAULT_REVIEW_DIR = "build/llm-eval/review"


def canonical_json(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def object_sha256(value: object) -> str:
    return hashlib.sha256(canonical_json(value)).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f"{path.name}.tmp-{uuid.uuid4()}")
    try:
        with temporary.open("x", encoding="utf-8") as output:
            json.dump(value, output, ensure_ascii=False, indent=2)
            output.write("\n")
        os.link(temporary, path)
        temporary.unlink()
    finally:
        temporary.unlink(missing_ok=True)


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f"{path.name}.tmp-{uuid.uuid4()}")
    try:
        with temporary.open("x", encoding="utf-8") as output:
            output.write(value)
        os.link(temporary, path)
        temporary.unlink()
    finally:
        temporary.unlink(missing_ok=True)


def load_dataset(
    repository: Path,
    manifest_value: str,
) -> tuple[Path, dict, dict[str, dict], list[str]]:
    manifest_path = evaluate.repository_path(repository, manifest_value)
    dataset = evaluate.load_json(manifest_path)
    if not isinstance(dataset, dict) or dataset.get("version") != 2:
        return manifest_path, {}, {}, ["review requires evaluation dataset v2"]
    failures, inputs = evaluate.validate_dataset(
        repository,
        manifest_path,
        dataset,
    )
    return manifest_path, dataset, inputs, failures


def automatic_gate(
    repository: Path,
    dataset: dict,
    inputs: dict[str, dict],
    responses_dir: Path,
    require_responses: bool,
) -> tuple[list[str], dict]:
    return evaluate.evaluate_dataset_responses(
        repository,
        dataset,
        inputs,
        responses_dir,
        require_responses,
    )


def missing_response_count(report: dict) -> int:
    return sum(
        metrics.get("missingResponses", 0)
        for metrics in report.get("automaticMetrics", {}).values()
    )


def failure_configuration(dataset: dict, failure: str) -> str | None:
    for case in dataset.get("cases", []):
        case_id = case.get("id")
        for configuration in dataset.get("configurations", []):
            configuration_id = configuration.get("id")
            if failure.startswith(f"{case_id}/{configuration_id}:"):
                return configuration_id
    return None


def review_eligibility_failures(
    dataset: dict,
    response_failures: list[str],
    report: dict,
    baseline: str,
    candidate: str,
) -> list[str]:
    configured = {
        configuration.get("id")
        for configuration in dataset.get("configurations", [])
    }
    if (
        baseline not in configured
        or candidate not in configured
        or baseline == candidate
    ):
        return ["baseline and candidate must be different configured ids"]

    blocking: list[str] = []
    baseline_violations: list[str] = []
    integrity_markers = ("missing response", "unreadable response")
    matrix_complete = True
    for failure in response_failures:
        configuration = failure_configuration(dataset, failure)
        is_integrity_failure = any(
            marker in failure for marker in integrity_markers
        )
        if is_integrity_failure or configuration is None:
            matrix_complete = False
        if configuration == baseline and not is_integrity_failure:
            baseline_violations.append(failure)
        else:
            blocking.append(failure)

    expected = len(dataset.get("cases", []))
    metrics = report.get("automaticMetrics", {})
    if set(metrics) != configured:
        matrix_complete = False
        blocking.append(
            "automatic metrics do not contain the exact configured matrix"
        )
    if report.get("caseCount") != expected:
        matrix_complete = False
        blocking.append("automatic report case count differs from dataset")
    if report.get("configurationCount") != len(configured):
        matrix_complete = False
        blocking.append(
            "automatic report configuration count differs from dataset"
        )
    for configuration_id in configured:
        values = metrics.get(configuration_id, {})
        if values.get("expectedResponses") != expected:
            matrix_complete = False
            blocking.append(
                f"{configuration_id}: expected response count differs from dataset"
            )
        if values.get("evaluatedResponses") != expected:
            matrix_complete = False
            blocking.append(
                f"{configuration_id}: matrix is incomplete or unreadable"
            )
        if values.get("missingResponses") != 0:
            matrix_complete = False
            blocking.append(
                f"{configuration_id}: matrix contains missing responses"
            )

    expected_total = expected * len(configured)
    if report.get("evaluatedResponses") != expected_total:
        matrix_complete = False
        blocking.append("evaluated response count differs from the complete matrix")

    candidate_metrics = metrics.get(candidate, {})
    if candidate_metrics.get("violationCount") != 0:
        blocking.append(f"{candidate}: candidate has automatic violations")
    if candidate_metrics.get("passedResponses") != expected:
        blocking.append(f"{candidate}: not every candidate response passed")

    blocking = list(dict.fromkeys(blocking))
    report["reviewEligibility"] = {
        "baselineConfigurationId": baseline,
        "candidateConfigurationId": candidate,
        "matrixComplete": matrix_complete,
        "baselineViolationCount": len(baseline_violations),
        "candidateViolationCount": candidate_metrics.get("violationCount"),
        "blockingViolationCount": len(blocking),
        "candidateEligibleForBlindedReview": not blocking,
    }
    return blocking


def counterbalanced_flips(dataset_sha256: str, case_ids: list[str]) -> set[str]:
    ranked = sorted(
        case_ids,
        key=lambda case_id: hashlib.sha256(
            f"{dataset_sha256}:{case_id}".encode("utf-8")
        ).hexdigest(),
    )
    return set(ranked[: len(ranked) // 2])


def indexed_findings(values: list[str], prefix: str) -> list[dict]:
    return [
        {"id": f"{prefix}-{index}", "text": text}
        for index, text in enumerate(values, start=1)
    ]


def build_review_artifacts(
    dataset: dict,
    inputs: dict[str, dict],
    responses_dir: Path,
    dataset_sha256: str,
    automatic_report: dict,
) -> tuple[dict, dict, dict]:
    configurations = dataset["configurations"]
    if len(configurations) != 2:
        raise ValueError("blinded A/B review requires exactly two configurations")

    configuration_ids = [configuration["id"] for configuration in configurations]
    case_ids = [case["id"] for case in dataset["cases"]]
    flipped = counterbalanced_flips(dataset_sha256, case_ids)
    assignments: list[dict] = []
    packet_cases: list[dict] = []

    for case in dataset["cases"]:
        case_id = case["id"]
        ordered_configurations = (
            list(reversed(configuration_ids))
            if case_id in flipped
            else list(configuration_ids)
        )
        variants: list[dict] = []
        for alias, configuration_id in zip(("A", "B"), ordered_configurations):
            relative_path = Path(case_id) / f"{configuration_id}.json"
            response_path = responses_dir / relative_path
            response = evaluate.load_json(response_path)
            response_hash = file_sha256(response_path)
            review_id = str(uuid.uuid5(
                REVIEW_NAMESPACE,
                f"{dataset_sha256}:{case_id}:{configuration_id}:{response_hash}",
            ))
            variants.append({
                "alias": alias,
                "reviewId": review_id,
                "responseSha256": response_hash,
                "response": response,
            })
            assignments.append({
                "reviewId": review_id,
                "caseId": case_id,
                "alias": alias,
                "configurationId": configuration_id,
                "responseRelativePath": relative_path.as_posix(),
                "responseSha256": response_hash,
            })

        expectations = case["expectations"]
        packet_cases.append({
            "caseId": case_id,
            "title": case["title"],
            "tags": case["tags"],
            "providerInput": inputs[case_id],
            "expectations": {
                "requiredFindings": indexed_findings(
                    expectations.get("requiredFindings", []),
                    "required",
                ),
                "acceptableFindings": indexed_findings(
                    expectations.get("acceptableFindings", []),
                    "acceptable",
                ),
                "forbiddenFindings": indexed_findings(
                    expectations.get("forbiddenFindings", []),
                    "forbidden",
                ),
            },
            "variants": variants,
        })

    assignment_commitment = {
        "datasetSha256": dataset_sha256,
        "assignments": assignments,
    }
    assignment_sha256 = object_sha256(assignment_commitment)
    packet = {
        "reviewVersion": REVIEW_VERSION,
        "datasetVersion": dataset["version"],
        "datasetSha256": dataset_sha256,
        "assignmentSha256": assignment_sha256,
        "instructions": [
            "Оценивать варианты A и B независимо; конфигурации намеренно скрыты.",
            "Обязательные выводы должны быть отражены по смыслу, а не дословно.",
            "Допустимые выводы не являются обязательными.",
            "Каждый запрещённый вывод и каждый тип critical error проверить отдельно.",
        ],
        "rubric": {
            "scale": dataset["humanRubric"]["scale"],
            "dimensions": dataset["humanRubric"]["dimensions"],
            "criticalErrors": indexed_findings(
                dataset["humanRubric"]["criticalErrors"],
                "critical",
            ),
        },
        "caseCount": len(packet_cases),
        "responseCount": len(assignments),
        "cases": packet_cases,
    }
    packet_sha256 = object_sha256(packet)
    mapping = {
        "reviewVersion": REVIEW_VERSION,
        "datasetSha256": dataset_sha256,
        "packetSha256": packet_sha256,
        "assignmentSha256": assignment_sha256,
        "automaticReportSha256": object_sha256(automatic_report),
        "assignments": assignments,
    }
    scores = score_template(packet, packet_sha256)
    return packet, mapping, scores


def score_template(packet: dict, packet_sha256: str) -> dict:
    dimensions = [dimension["id"] for dimension in packet["rubric"]["dimensions"]]
    critical_errors = packet["rubric"]["criticalErrors"]
    evaluations: list[dict] = []
    for case in packet["cases"]:
        required_ids = [item["id"] for item in case["expectations"]["requiredFindings"]]
        forbidden_ids = [item["id"] for item in case["expectations"]["forbiddenFindings"]]
        for variant in case["variants"]:
            evaluations.append({
                "reviewId": variant["reviewId"],
                "dimensionScores": {dimension: None for dimension in dimensions},
                "requiredFindings": [
                    {"findingId": finding_id, "status": "UNSCORED", "notes": ""}
                    for finding_id in required_ids
                ],
                "forbiddenFindings": [
                    {"findingId": finding_id, "status": "UNSCORED", "notes": ""}
                    for finding_id in forbidden_ids
                ],
                "criticalErrors": [
                    {"errorId": error["id"], "status": "UNSCORED", "notes": ""}
                    for error in critical_errors
                ],
                "notes": "",
            })
    return {
        "reviewVersion": REVIEW_VERSION,
        "datasetSha256": packet["datasetSha256"],
        "packetSha256": packet_sha256,
        "assignmentSha256": packet["assignmentSha256"],
        "reviewerId": "TODO",
        "evaluations": evaluations,
    }


def packet_index(packet: dict) -> dict[str, tuple[dict, dict]]:
    result: dict[str, tuple[dict, dict]] = {}
    for case in packet.get("cases", []):
        for variant in case.get("variants", []):
            review_id = variant.get("reviewId")
            if review_id in result:
                raise ValueError(f"duplicate reviewId in packet: {review_id}")
            result[review_id] = (case, variant)
    return result


def assignment_commitment(mapping: dict) -> dict:
    return {
        "datasetSha256": mapping.get("datasetSha256"),
        "assignments": mapping.get("assignments"),
    }


def exact_status_items(
    actual: object,
    expected_ids: list[str],
    id_field: str,
    allowed_statuses: set[str],
    prefix: str,
) -> list[str]:
    if not isinstance(actual, list):
        return [f"{prefix}: must be an array"]
    actual_ids = [item.get(id_field) for item in actual if isinstance(item, dict)]
    failures: list[str] = []
    if actual_ids != expected_ids or len(actual_ids) != len(actual):
        failures.append(f"{prefix}: ids or order differ from review packet")
    for index, item in enumerate(actual):
        if not isinstance(item, dict) or item.get("status") not in allowed_statuses:
            failures.append(f"{prefix}[{index}]: assessment is incomplete")
    return failures


def validate_completed_scores(
    repository: Path,
    packet: dict,
    mapping: dict,
    scores: object,
) -> list[str]:
    schema = evaluate.load_json(
        repository / "scripts/llm-eval/review-scores-v1.schema.json"
    )
    failures = evaluate.schema_failures(
        Draft202012Validator(schema),
        scores,
        "scores",
    )
    if failures or not isinstance(scores, dict):
        return failures

    expected_packet_hash = object_sha256(packet)
    expected_assignment_hash = object_sha256(assignment_commitment(mapping))
    bindings = {
        "datasetSha256": packet.get("datasetSha256"),
        "packetSha256": expected_packet_hash,
        "assignmentSha256": expected_assignment_hash,
    }
    for field, expected in bindings.items():
        if mapping.get(field) != expected and field != "datasetSha256":
            failures.append(f"mapping: invalid {field}")
        if scores.get(field) != expected:
            failures.append(f"scores: invalid {field}")
    if mapping.get("datasetSha256") != bindings["datasetSha256"]:
        failures.append("mapping: invalid datasetSha256")
    if packet.get("assignmentSha256") != expected_assignment_hash:
        failures.append("packet: invalid assignmentSha256")
    if not str(scores.get("reviewerId", "")).strip() or scores.get("reviewerId") == "TODO":
        failures.append("scores: reviewerId must be filled")

    try:
        variants = packet_index(packet)
    except ValueError as exception:
        return [*failures, str(exception)]
    evaluations = scores.get("evaluations", [])
    evaluation_ids = [
        item.get("reviewId") for item in evaluations if isinstance(item, dict)
    ]
    if set(evaluation_ids) != set(variants) or len(evaluation_ids) != len(variants):
        failures.append("scores: evaluations must contain every reviewId exactly once")
        return failures

    dimension_ids = [item["id"] for item in packet["rubric"]["dimensions"]]
    scale = packet["rubric"]["scale"]
    critical_ids = [item["id"] for item in packet["rubric"]["criticalErrors"]]
    for evaluation in evaluations:
        review_id = evaluation["reviewId"]
        case, _ = variants[review_id]
        prefix = f"scores/{case['caseId']}/{review_id}"
        dimensions = evaluation.get("dimensionScores", {})
        if set(dimensions) != set(dimension_ids):
            failures.append(f"{prefix}: dimension ids differ from rubric")
        else:
            for dimension_id, value in dimensions.items():
                if (
                    type(value) is not int
                    or value < scale["min"]
                    or value > scale["max"]
                ):
                    failures.append(
                        f"{prefix}: {dimension_id} must be an integer "
                        f"from {scale['min']} to {scale['max']}"
                    )
        required_ids = [
            item["id"] for item in case["expectations"]["requiredFindings"]
        ]
        forbidden_ids = [
            item["id"] for item in case["expectations"]["forbiddenFindings"]
        ]
        failures.extend(exact_status_items(
            evaluation.get("requiredFindings"),
            required_ids,
            "findingId",
            {"COVERED", "MISSING"},
            f"{prefix}/requiredFindings",
        ))
        failures.extend(exact_status_items(
            evaluation.get("forbiddenFindings"),
            forbidden_ids,
            "findingId",
            {"ABSENT", "PRESENT"},
            f"{prefix}/forbiddenFindings",
        ))
        failures.extend(exact_status_items(
            evaluation.get("criticalErrors"),
            critical_ids,
            "errorId",
            {"ABSENT", "PRESENT"},
            f"{prefix}/criticalErrors",
        ))
    return failures


def verify_artifact_integrity(
    packet: dict,
    mapping: dict,
    responses_dir: Path,
) -> list[str]:
    failures: list[str] = []
    try:
        variants = packet_index(packet)
    except ValueError as exception:
        return [str(exception)]
    assignments = mapping.get("assignments", [])
    assignment_ids = [
        item.get("reviewId") for item in assignments if isinstance(item, dict)
    ]
    if set(assignment_ids) != set(variants) or len(assignment_ids) != len(variants):
        return ["mapping: assignments differ from packet"]
    root = responses_dir.resolve()
    for assignment in assignments:
        review_id = assignment["reviewId"]
        relative = Path(assignment["responseRelativePath"])
        response_path = (root / relative).resolve()
        try:
            response_path.relative_to(root)
        except ValueError:
            failures.append(f"{review_id}: response path escapes responses directory")
            continue
        if not response_path.is_file():
            failures.append(f"{review_id}: response artifact is missing")
            continue
        actual_hash = file_sha256(response_path)
        _, variant = variants[review_id]
        if actual_hash != assignment["responseSha256"]:
            failures.append(f"{review_id}: response hash differs from mapping")
        if actual_hash != variant["responseSha256"]:
            failures.append(f"{review_id}: response hash differs from packet")
        if evaluate.load_json(response_path) != variant["response"]:
            failures.append(f"{review_id}: response body differs from packet")
    return failures


def rounded(value: float) -> float:
    return round(value, 4)


def manual_report(packet: dict, mapping: dict, scores: dict) -> dict:
    assignments = {item["reviewId"]: item for item in mapping["assignments"]}
    variants = packet_index(packet)
    dimensions = [item["id"] for item in packet["rubric"]["dimensions"]]
    pass_average = packet["rubric"]["scale"]["passAverage"]
    results: dict[str, list[dict]] = defaultdict(list)

    for evaluation in scores["evaluations"]:
        review_id = evaluation["reviewId"]
        assignment = assignments[review_id]
        case, _ = variants[review_id]
        dimension_scores = evaluation["dimensionScores"]
        average_score = sum(dimension_scores.values()) / len(dimensions)
        missing_required = sum(
            item["status"] == "MISSING"
            for item in evaluation["requiredFindings"]
        )
        present_forbidden = sum(
            item["status"] == "PRESENT"
            for item in evaluation["forbiddenFindings"]
        )
        critical_errors = sum(
            item["status"] == "PRESENT"
            for item in evaluation["criticalErrors"]
        )
        passed = (
            average_score >= pass_average
            and missing_required == 0
            and present_forbidden == 0
            and critical_errors == 0
        )
        results[assignment["configurationId"]].append({
            "caseId": case["caseId"],
            "alias": assignment["alias"],
            "reviewId": review_id,
            "averageScore": rounded(average_score),
            "dimensionScores": dimension_scores,
            "missingRequiredFindings": missing_required,
            "presentForbiddenFindings": present_forbidden,
            "criticalErrors": critical_errors,
            "passed": passed,
        })

    configurations: dict[str, dict] = {}
    for configuration_id, response_results in results.items():
        count = len(response_results)
        dimension_averages = {
            dimension: rounded(sum(
                item["dimensionScores"][dimension]
                for item in response_results
            ) / count)
            for dimension in dimensions
        }
        passed_count = sum(item["passed"] for item in response_results)
        configurations[configuration_id] = {
            "responseCount": count,
            "passedResponses": passed_count,
            "passRate": rounded(passed_count / count),
            "averageScore": rounded(sum(
                item["averageScore"] for item in response_results
            ) / count),
            "dimensionAverages": dimension_averages,
            "missingRequiredFindings": sum(
                item["missingRequiredFindings"] for item in response_results
            ),
            "presentForbiddenFindings": sum(
                item["presentForbiddenFindings"] for item in response_results
            ),
            "criticalErrors": sum(
                item["criticalErrors"] for item in response_results
            ),
            "eligible": passed_count == count,
            "responses": response_results,
        }
    return {
        "passAverage": pass_average,
        "reviewerId": scores["reviewerId"],
        "configurations": configurations,
    }


def metric_value(automatic: dict, configuration: str, group: str, key: str) -> float:
    value = automatic["automaticMetrics"][configuration]
    if group:
        value = value.get(group, {})
    result = value.get(key)
    return float(result) if result is not None else 0.0


def build_decision(
    dataset: dict,
    manual: dict,
    automatic: dict,
    baseline: str,
    candidate: str,
) -> dict:
    known = {configuration["id"] for configuration in dataset["configurations"]}
    if baseline not in known or candidate not in known or baseline == candidate:
        raise ValueError("baseline and candidate must be different configured ids")
    baseline_manual = manual["configurations"][baseline]
    candidate_manual = manual["configurations"][candidate]

    comparisons = [
        {
            "id": "candidate-manual-gate",
            "passed": candidate_manual["eligible"],
            "candidate": candidate_manual["passRate"],
            "baseline": baseline_manual["passRate"],
        },
        {
            "id": "manual-pass-rate-non-inferior",
            "passed": candidate_manual["passRate"] >= baseline_manual["passRate"],
            "candidate": candidate_manual["passRate"],
            "baseline": baseline_manual["passRate"],
        },
        {
            "id": "manual-average-non-inferior",
            "passed": candidate_manual["averageScore"] >= baseline_manual["averageScore"],
            "candidate": candidate_manual["averageScore"],
            "baseline": baseline_manual["averageScore"],
        },
        {
            "id": "every-dimension-non-inferior",
            "passed": all(
                candidate_manual["dimensionAverages"][dimension]
                >= baseline_manual["dimensionAverages"][dimension]
                for dimension in candidate_manual["dimensionAverages"]
            ),
            "candidate": candidate_manual["dimensionAverages"],
            "baseline": baseline_manual["dimensionAverages"],
        },
    ]
    for key, comparison_id in (
        ("criticalErrors", "critical-errors-non-inferior"),
        ("missingRequiredFindings", "required-findings-non-inferior"),
        ("presentForbiddenFindings", "forbidden-findings-non-inferior"),
    ):
        comparisons.append({
            "id": comparison_id,
            "passed": candidate_manual[key] <= baseline_manual[key],
            "candidate": candidate_manual[key],
            "baseline": baseline_manual[key],
        })

    automatic_comparisons = (
        ("", "passRate", "automatic-pass-rate-non-inferior", ">="),
        ("averages", "requiredCandidateCoverage", "candidate-coverage-non-inferior", ">="),
        ("averages", "duplicateNarratives", "duplicates-non-inferior", "<="),
        ("averages", "nearDuplicateNarratives", "near-narratives-non-inferior", "<="),
        ("averages", "nearDuplicatePrimaryTeamOverviews", "team-overview-duplicates-non-inferior", "<="),
        ("averages", "directiveInsights", "insight-directives-non-inferior", "<="),
        ("averages", "unsupportedCauseNarratives", "unsupported-causes-non-inferior", "<="),
        ("averages", "nonSpecificActions", "action-specificity-non-inferior", "<="),
        ("averages", "nearDuplicateActions", "action-duplicates-non-inferior", "<="),
    )
    for group, key, comparison_id, operator in automatic_comparisons:
        baseline_value = metric_value(automatic, baseline, group, key)
        candidate_value = metric_value(automatic, candidate, group, key)
        comparisons.append({
            "id": comparison_id,
            "passed": (
                candidate_value >= baseline_value
                if operator == ">="
                else candidate_value <= baseline_value
            ),
            "candidate": candidate_value,
            "baseline": baseline_value,
        })

    passed = all(item["passed"] for item in comparisons)
    code = (
        "CANDIDATE_ELIGIBLE_FOR_CANARY"
        if passed
        else "KEEP_BASELINE_AND_REVISE_CANDIDATE"
    )
    return {
        "code": code,
        "candidateEligibleForCanary": passed,
        "baselineConfigurationId": baseline,
        "candidateConfigurationId": candidate,
        "comparisons": comparisons,
        "informationalAutomaticMetrics": {
            configuration: automatic["automaticMetrics"][configuration].get("averages", {})
            for configuration in (baseline, candidate)
        },
        "note": (
            "Допуск означает только готовность к отдельному canary одного периода; "
            "он не включает публикацию, Telegram или смену default prompt."
        ),
    }


def decision_markdown(report: dict) -> str:
    decision = report["decision"]
    lines = [
        "# Итог слепой оценки ИИ-интерпретаций",
        "",
        f"Решение: `{decision['code']}`.",
        "",
        f"Baseline: `{decision['baselineConfigurationId']}`; "
        f"candidate: `{decision['candidateConfigurationId']}`.",
        "",
        decision["note"],
        "",
        "## Ручная оценка",
        "",
        "| Конфигурация | Пройдено | Средняя оценка | Critical errors | "
        "Пропущено обязательных | Запрещённых выводов |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for configuration, metrics in report["manualReview"]["configurations"].items():
        lines.append(
            f"| {configuration} | {metrics['passedResponses']}/{metrics['responseCount']} | "
            f"{metrics['averageScore']:.4f} | {metrics['criticalErrors']} | "
            f"{metrics['missingRequiredFindings']} | "
            f"{metrics['presentForbiddenFindings']} |"
        )
    lines.extend(["", "## Контрольные сравнения", ""])
    for comparison in decision["comparisons"]:
        marker = "PASS" if comparison["passed"] else "FAIL"
        lines.append(
            f"- `{marker}` {comparison['id']}: candidate="
            f"{comparison['candidate']}, baseline={comparison['baseline']}"
        )
    lines.append("")
    return "\n".join(lines)


def command_status(repository: Path, arguments: argparse.Namespace) -> int:
    _, dataset, inputs, failures = load_dataset(repository, arguments.manifest)
    responses_dir = evaluate.repository_path(repository, arguments.responses_dir)
    if not failures:
        response_failures, report = automatic_gate(
            repository, dataset, inputs, responses_dir, False
        )
        failures.extend(review_eligibility_failures(
            dataset, response_failures, report,
            arguments.baseline, arguments.candidate,
        ))
    else:
        report = {"automaticMetrics": {}, "evaluatedResponses": 0}
    missing = missing_response_count(report)
    ready = not failures and missing == 0
    print(json.dumps({
        "readyForBlindedReview": ready,
        "evaluatedResponses": report.get("evaluatedResponses", 0),
        "missingResponses": missing,
        "automaticMetrics": report.get("automaticMetrics", {}),
        "reviewEligibility": report.get("reviewEligibility"),
        "violations": failures,
    }, ensure_ascii=False, indent=2))
    return 1 if failures else 0


def command_prepare(repository: Path, arguments: argparse.Namespace) -> int:
    manifest_path, dataset, inputs, failures = load_dataset(
        repository, arguments.manifest
    )
    responses_dir = evaluate.repository_path(repository, arguments.responses_dir)
    if not failures:
        response_failures, automatic_report = automatic_gate(
            repository, dataset, inputs, responses_dir, True
        )
        failures.extend(review_eligibility_failures(
            dataset, response_failures, automatic_report,
            arguments.baseline, arguments.candidate,
        ))
    else:
        automatic_report = {}
    if failures:
        raise ValueError(
            f"automatic gate failed with {len(failures)} violation(s): "
            + "; ".join(failures[:5])
        )
    packet, mapping, scores = build_review_artifacts(
        dataset,
        inputs,
        responses_dir,
        file_sha256(manifest_path),
        automatic_report,
    )
    output_dir = evaluate.repository_path(repository, arguments.output_dir)
    targets = {
        output_dir / "packet.json": packet,
        output_dir / "assignments.json": mapping,
        output_dir / "automatic-report.json": automatic_report,
        output_dir / "scores.json": scores,
    }
    existing = [str(path) for path in targets if path.exists()]
    if existing:
        raise ValueError(f"refusing to overwrite review artifacts: {existing}")
    for path, value in targets.items():
        write_json(path, value)
    print(
        f"Blinded review prepared: {packet['caseCount']} cases, "
        f"{packet['responseCount']} responses in {output_dir}."
    )
    return 0


def command_finalize(repository: Path, arguments: argparse.Namespace) -> int:
    manifest_path, dataset, inputs, failures = load_dataset(
        repository, arguments.manifest
    )
    review_dir = evaluate.repository_path(repository, arguments.review_dir)
    responses_dir = evaluate.repository_path(repository, arguments.responses_dir)
    packet = evaluate.load_json(review_dir / "packet.json")
    mapping = evaluate.load_json(review_dir / "assignments.json")
    scores = evaluate.load_json(review_dir / "scores.json")
    saved_automatic = evaluate.load_json(review_dir / "automatic-report.json")
    artifacts = {
        "packet": packet,
        "mapping": mapping,
        "scores": scores,
        "automatic report": saved_automatic,
    }
    invalid_artifacts = [
        name for name, value in artifacts.items() if not isinstance(value, dict)
    ]
    if invalid_artifacts:
        raise ValueError(f"review artifacts must be objects: {invalid_artifacts}")

    if not failures:
        response_failures, automatic_report = automatic_gate(
            repository, dataset, inputs, responses_dir, True
        )
        failures.extend(review_eligibility_failures(
            dataset, response_failures, automatic_report,
            arguments.baseline, arguments.candidate,
        ))
    else:
        automatic_report = {}
    expected_dataset_hash = file_sha256(manifest_path)
    if packet.get("datasetSha256") != expected_dataset_hash:
        failures.append("packet: dataset hash differs from current manifest")
    if mapping.get("automaticReportSha256") != object_sha256(saved_automatic):
        failures.append("mapping: saved automatic report hash differs")
    if saved_automatic != automatic_report:
        failures.append("automatic report differs after re-evaluation")
    failures.extend(verify_artifact_integrity(packet, mapping, responses_dir))
    failures.extend(validate_completed_scores(repository, packet, mapping, scores))
    if failures:
        raise ValueError(
            f"review finalization failed with {len(failures)} violation(s): "
            + "; ".join(failures[:8])
        )

    manual = manual_report(packet, mapping, scores)
    decision = build_decision(
        dataset,
        manual,
        automatic_report,
        arguments.baseline,
        arguments.candidate,
    )
    report = {
        "reviewVersion": REVIEW_VERSION,
        "datasetVersion": dataset["version"],
        "datasetSha256": expected_dataset_hash,
        "packetSha256": object_sha256(packet),
        "automaticEvaluation": automatic_report,
        "manualReview": manual,
        "decision": decision,
    }
    report_path = evaluate.repository_path(repository, arguments.report)
    markdown_path = evaluate.repository_path(repository, arguments.markdown)
    existing = [str(path) for path in (report_path, markdown_path) if path.exists()]
    if existing:
        raise ValueError(f"refusing to overwrite decision artifacts: {existing}")
    write_json(report_path, report)
    write_text(markdown_path, decision_markdown(report))
    print(f"Review finalized: {decision['code']}.")
    return 0


def add_common_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST)
    parser.add_argument("--responses-dir", default=DEFAULT_RESPONSES)


def add_gate_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--baseline", default="v4")
    parser.add_argument("--candidate", default="v15")


def main() -> int:
    os.umask(0o077)
    parser = argparse.ArgumentParser(
        description="Prepare and finalize blinded A/B review for LLM responses"
    )
    commands = parser.add_subparsers(dest="command", required=True)
    status = commands.add_parser("status", help="Check readiness without writing artifacts")
    add_common_arguments(status)
    add_gate_arguments(status)
    prepare = commands.add_parser("prepare", help="Create blinded packet and blank scores")
    add_common_arguments(prepare)
    add_gate_arguments(prepare)
    prepare.add_argument("--output-dir", default=DEFAULT_REVIEW_DIR)
    finalize = commands.add_parser("finalize", help="Validate scores and unblind decision")
    add_common_arguments(finalize)
    finalize.add_argument("--review-dir", default=DEFAULT_REVIEW_DIR)
    finalize.add_argument("--baseline", default="v4")
    finalize.add_argument("--candidate", default="v15")
    finalize.add_argument(
        "--report",
        default="build/llm-eval/review/decision-report.json",
    )
    finalize.add_argument(
        "--markdown",
        default="build/llm-eval/review/decision-report.md",
    )
    arguments = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    try:
        if arguments.command == "status":
            return command_status(repository, arguments)
        if arguments.command == "prepare":
            return command_prepare(repository, arguments)
        return command_finalize(repository, arguments)
    except (OSError, ValueError, json.JSONDecodeError, KeyError) as exception:
        print(f"LLM review failed: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

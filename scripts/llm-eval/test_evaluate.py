#!/usr/bin/env python3

from __future__ import annotations

import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts" / "llm-eval" / "evaluate.py"
DATASET_PATH = ROOT / "scripts" / "llm-eval" / "dataset-v2.json"

SPEC = importlib.util.spec_from_file_location("llm_evaluate", MODULE_PATH)
EVALUATE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(EVALUATE)


def load_dataset() -> dict:
    return json.loads(DATASET_PATH.read_text(encoding="utf-8"))


def case_by_id(dataset: dict, case_id: str) -> dict:
    return next(case for case in dataset["cases"] if case["id"] == case_id)


def first_evidence(payload: dict, scope: str, employee_ref: str | None = None) -> str:
    for item in payload["manifest"]["evidence"]:
        if (
            item["scope"] == scope
            and item["employeeRef"] == employee_ref
            and item["available"]
        ):
            return item["evidenceRef"]
    raise AssertionError(f"no available {scope} evidence for {employee_ref}")


def valid_output(payload: dict) -> dict:
    employee_words = ["первого", "второго", "третьего", "четвёртого"]
    summaries = [
        {
            "scope": "STORE",
            "employeeRef": None,
            "section": "HEADLINE",
            "categoryCode": None,
            "text": "Главный результат магазина отражён в доступных фактах.",
            "evidenceRefs": [first_evidence(payload, "STORE")],
        },
        {
            "scope": "TEAM",
            "employeeRef": None,
            "section": "TEAM_OVERVIEW",
            "categoryCode": None,
            "text": "Командный результат отражён в доступных фактах.",
            "evidenceRefs": [first_evidence(payload, "TEAM")],
        },
    ]
    for index, employee in enumerate(payload["facts"]["employees"]):
        summaries.append({
            "scope": "EMPLOYEE",
            "employeeRef": employee["employeeRef"],
            "section": "HEADLINE",
            "categoryCode": None,
            "text": (
                "Доступный результат "
                f"{employee_words[index]} сотрудника отражён без домыслов."
            ),
            "evidenceRefs": [
                first_evidence(payload, "EMPLOYEE", employee["employeeRef"])
            ],
        })
    limitations = []
    for source in payload["manifest"]["limitations"]:
        limitations.append({
            **copy.deepcopy(source),
            "summary": "Показатель недоступен или ограничен для этой части анализа.",
        })
    return {
        "employees": [
            {
                "employeeRef": employee["employeeRef"],
                "analysisStatus": employee["analysisStatus"],
            }
            for employee in payload["facts"]["employees"]
        ],
        "summaryBlocks": summaries,
        "insights": [],
        "actions": [],
        "teamRelationships": [],
        "dataLimitations": limitations,
    }


def valid_v3_output(payload: dict) -> dict:
    output = valid_output(payload)
    output["summaryBlocks"] = [
        summary
        for summary in output["summaryBlocks"]
        if not (
            summary["scope"] == "STORE"
            and summary["section"] == "HEADLINE"
        )
    ]
    candidates = EVALUATE.store_candidates(payload)
    if not candidates:
        output["primarySignal"] = None
        return output
    candidate = candidates[0]
    output["primarySignal"] = {
        "scope": "STORE",
        "employeeRef": None,
        "categoryCode": candidate["categoryCode"],
        "kind": candidate["kind"],
        "theme": candidate["theme"],
        "candidateRef": candidate["candidateRef"],
        "text": "Главное подтверждённое изменение требует внимания.",
        "evidenceRefs": list(candidate["evidenceRefs"]),
    }
    return output

def valid_v19_transport(payload: dict) -> dict:
    canonical = valid_v3_output(payload)
    team = next(
        summary
        for summary in canonical["summaryBlocks"]
        if summary["scope"] == "TEAM"
    )
    transport = {
        key: copy.deepcopy(value)
        for key, value in canonical.items()
        if key not in {"employees", "summaryBlocks", "dataLimitations"}
    }
    transport["teamOverview"] = {
        "text": team["text"],
        "evidenceRefs": team["evidenceRefs"],
    }
    transport["backendEmployeeHeadlines"] = True
    transport["supportingSummaries"] = []
    transport["teamRelationships"] = []
    return transport


def store_action(
    payload: dict,
    action_type: str,
    title: str,
    summary: str,
) -> dict:
    return {
        "type": action_type,
        "title": title,
        "summary": summary,
        "evidenceRefs": [first_evidence(payload, "STORE")],
        "targetScope": "STORE",
        "targetEmployeeRefs": [],
        "horizon": "NEXT_WEEK",
    }


class EvaluationDatasetTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.dataset = load_dataset()
        cls.validator = Draft202012Validator(
            EVALUATE.load_json(ROOT / cls.dataset["outputSchema"]),
            format_checker=FormatChecker(),
        )
        v3_schema = cls.dataset["configurations"][1]["outputSchema"]
        cls.v3_validator = Draft202012Validator(
            EVALUATE.load_json(ROOT / v3_schema),
            format_checker=FormatChecker(),
        )
        cls.dataset_failures, cls.inputs = EVALUATE.validate_dataset(
            ROOT,
            DATASET_PATH,
            cls.dataset,
        )

    def validate(self, case_id: str, output: dict) -> list[str]:
        case = case_by_id(self.dataset, case_id)
        failures, _ = EVALUATE.validate_response(
            self.validator,
            self.dataset,
            case,
            self.inputs[case_id],
            output,
            "test",
        )
        return failures

    def test_candidate_configuration_is_primary_signal_v19(self):
        configurations = self.dataset["configurations"]

        self.assertEqual(["v4", "v19"], [
            value["id"] for value in configurations
        ])
        self.assertEqual(
            "weekly-interpretation-v19",
            configurations[1]["promptVersion"],
        )

    def test_v20_and_v21_use_privacy_reduced_validation_path(self):
        self.assertTrue(EVALUATE.is_privacy_reduced_prompt({
            "promptVersion": "weekly-interpretation-v20",
        }))
        self.assertTrue(EVALUATE.is_privacy_reduced_prompt({
            "promptVersion": "weekly-interpretation-v21",
        }))
        self.assertFalse(EVALUATE.is_privacy_reduced_prompt({
            "promptVersion": "weekly-interpretation-v18",
        }))

    def test_v19_primary_signal_satisfies_required_candidate(self):
        case_id = "accessory-gap"
        case = case_by_id(self.dataset, case_id)
        output = valid_v19_transport(self.inputs[case_id])

        failures, metrics = EVALUATE.validate_response(
            self.v3_validator,
            self.dataset,
            case,
            self.inputs[case_id],
            output,
            "v19",
        )

        self.assertEqual([], failures)
        self.assertEqual(1, metrics["primarySignals"])
        self.assertEqual(1.0, metrics["requiredCandidateCoverage"])

    def test_v19_rejects_primary_candidate_repeated_in_insights(self):
        case_id = "accessory-gap"
        case = case_by_id(self.dataset, case_id)
        output = valid_v19_transport(self.inputs[case_id])
        primary = output["primarySignal"]
        output["insights"] = [{
            "scope": "STORE",
            "employeeRef": None,
            "categoryCode": primary["categoryCode"],
            "kind": primary["kind"],
            "theme": primary["theme"],
            "candidateRef": primary["candidateRef"],
            "title": "Повтор главного сигнала",
            "summary": "Главное изменение повторено во вторичной карточке.",
            "evidenceRefs": list(primary["evidenceRefs"]),
        }]

        failures, _ = EVALUATE.validate_response(
            self.v3_validator,
            self.dataset,
            case,
            self.inputs[case_id],
            output,
            "v19",
        )

        self.assertTrue(any(
            "duplicate candidateRef across primary and insights" in failure
            for failure in failures
        ))

    def test_v19_accepts_null_primary_without_store_candidate(self):
        case_id = "stable-week"
        case = case_by_id(self.dataset, case_id)
        output = valid_v19_transport(self.inputs[case_id])

        failures, metrics = EVALUATE.validate_response(
            self.v3_validator,
            self.dataset,
            case,
            self.inputs[case_id],
            output,
            "v19",
        )

        self.assertEqual([], failures)
        self.assertEqual(0, metrics["primarySignals"])

    def test_v19_rejects_provider_owned_employee_headlines(self):
        case_id = "accessory-gap"
        case = case_by_id(self.dataset, case_id)
        payload = self.inputs[case_id]
        canonical = valid_v3_output(payload)
        team = next(
            summary
            for summary in canonical["summaryBlocks"]
            if summary["scope"] == "TEAM"
        )
        headlines = {
            summary["employeeRef"]: {
                "text": summary["text"],
                "evidenceRefs": summary["evidenceRefs"],
            }
            for summary in canonical["summaryBlocks"]
            if summary["scope"] == "EMPLOYEE"
        }
        transport = {
            key: copy.deepcopy(value)
            for key, value in canonical.items()
            if key not in {"employees", "summaryBlocks", "dataLimitations"}
        }
        transport["teamOverview"] = {
            "text": team["text"],
            "evidenceRefs": team["evidenceRefs"],
        }
        transport["employeeHeadlines"] = headlines
        transport["supportingSummaries"] = []

        failures, metrics = EVALUATE.validate_response(
            self.v3_validator,
            self.dataset,
            case,
            payload,
            transport,
            "v19",
        )

        self.assertTrue(any(
            "backend-owned provider field at $.employeeHeadlines" in failure
            for failure in failures
        ))

    def test_v19_rejects_employee_candidate_not_sent_to_provider(self):
        payload = self.inputs["employee-improvement"]
        candidate = next(
            value
            for value in payload["facts"]["candidateSignals"]
            if value["employeeRef"] is not None
        )

        failures = EVALUATE.privacy_reduced_provider_failures(
            "employee-improvement/v19",
            {
                "primarySignal": {
                    "candidateRef": candidate["candidateRef"],
                    "employeeRef": candidate["employeeRef"],
                    "evidenceRefs": list(candidate["evidenceRefs"]),
                },
            },
            payload,
        )

        self.assertTrue(any(
            "provider candidate was not sent" in failure
            for failure in failures
        ))
        self.assertTrue(any(
            "provider employee reference" in failure
            for failure in failures
        ))

    def test_v19_backend_marker_builds_employee_and_team_content(self):
        case_id = "team-most-improved"
        case = case_by_id(self.dataset, case_id)
        payload = self.inputs[case_id]
        canonical = valid_v3_output(payload)
        team = next(
            summary
            for summary in canonical["summaryBlocks"]
            if summary["scope"] == "TEAM"
        )
        transport = {
            key: copy.deepcopy(value)
            for key, value in canonical.items()
            if key not in {"employees", "summaryBlocks", "dataLimitations"}
        }
        transport["teamOverview"] = {
            "text": team["text"],
            "evidenceRefs": team["evidenceRefs"],
        }
        transport["backendEmployeeHeadlines"] = True
        transport["supportingSummaries"] = []
        transport["teamRelationships"] = []

        failures, metrics = EVALUATE.validate_response(
            self.v3_validator,
            self.dataset,
            case,
            payload,
            transport,
            "v19",
        )

        self.assertEqual([], failures)
        normalized = EVALUATE.backend_normalize_response(transport, payload)
        self.assertNotIn("backendEmployeeHeadlines", normalized)
        employee_summaries = [
            summary for summary in normalized["summaryBlocks"]
            if summary["scope"] == "EMPLOYEE"
        ]
        self.assertEqual(
            len(payload["manifest"]["employeeRefs"]),
            len(employee_summaries),
        )
        self.assertEqual(1, metrics["teamRelationships"])

    def test_v19_backend_employee_headline_counts_exact_candidate(self):
        case_id = "employee-improvement"
        case = case_by_id(self.dataset, case_id)
        payload = self.inputs[case_id]
        canonical = valid_v3_output(payload)
        team = next(
            summary
            for summary in canonical["summaryBlocks"]
            if summary["scope"] == "TEAM"
        )
        transport = {
            key: copy.deepcopy(value)
            for key, value in canonical.items()
            if key not in {"employees", "summaryBlocks", "dataLimitations"}
        }
        transport["teamOverview"] = {
            "text": team["text"],
            "evidenceRefs": team["evidenceRefs"],
        }
        transport["backendEmployeeHeadlines"] = True
        transport["supportingSummaries"] = []
        transport["teamRelationships"] = []

        failures, metrics = EVALUATE.validate_response(
            self.v3_validator,
            self.dataset,
            case,
            payload,
            transport,
            "v19",
        )

        self.assertEqual([], failures)
        self.assertEqual(1.0, metrics["requiredCandidateCoverage"])
        self.assertEqual(1, metrics["candidateBackedSignals"])

    def test_backend_store_results_explain_neutral_and_limited_cases(self):
        expected = {
            "stable-week": (
                "Выручка магазина существенно не изменилась "
                "относительно прошлого периода."
            ),
            "plan-met": "План выполнен на целевом уровне.",
            "attach-small-denominator": (
                "База продаж недостаточна для надёжной оценки "
                "частоты дополнительных продаж."
            ),
            "zero-previous-base": (
                "Выручка категории появилась после нулевого "
                "значения прошлого периода."
            ),
        }

        for case_id, text in expected.items():
            with self.subTest(case_id=case_id):
                summary = EVALUATE.backend_store_summary(
                    self.inputs[case_id]
                )
                self.assertIsNotNone(summary)
                self.assertEqual("RESULT", summary["section"])
                self.assertEqual(text, summary["text"])
                self.assertEqual(1, len(summary["evidenceRefs"]))

    def test_backend_team_overview_states_an_exact_tie(self):
        overview = EVALUATE.backend_team_overview(
            self.inputs["team-tie-no-leader"]
        )

        self.assertEqual(
            "Результаты сотрудников по доступной компетенции равны.",
            overview["text"],
        )
        self.assertEqual(
            [
                "EMP:E01.RATING.STRUCTURE_SCORE.CURRENT",
                "EMP:E02.RATING.STRUCTURE_SCORE.CURRENT",
                "EMP:E03.RATING.STRUCTURE_SCORE.CURRENT",
            ],
            overview["evidenceRefs"],
        )

    def test_backend_employee_text_distinguishes_change_and_limited_data(self):
        decline = self.inputs["employee-decline"]
        candidate = next(
            value
            for value in decline["facts"]["candidateSignals"]
            if value["employeeRef"] == "E01"
        )
        _, decline_text = EVALUATE.candidate_narrative(candidate)
        limited = EVALUATE.backend_employee_headlines(
            self.inputs["employee-limited"]
        )

        self.assertEqual(
            "Результат сотрудника существенно снизился относительно "
            "его прошлого периода.",
            decline_text,
        )
        self.assertEqual(
            "По сотруднику доступен только ограниченный текущий результат.",
            limited["E01"]["text"],
        )

    def test_backend_limitations_name_the_affected_business_metrics(self):
        profit = self.inputs["profit-unavailable"]
        classification = self.inputs["classification-limited"]

        self.assertEqual(
            "Валовая прибыль и маржинальность недоступны из-за "
            "неполных данных о себестоимости.",
            EVALUATE.limitation_summary(
                profit["manifest"]["limitations"][0]
            ),
        )
        self.assertEqual(
            "Неполная классификация снижает уверенность в выводах "
            "по категориям и дополнительным продажам.",
            EVALUATE.limitation_summary(
                classification["manifest"]["limitations"][0]
            ),
        )

    def test_backend_candidate_text_uses_full_verified_context(self):
        zero = self.inputs["zero-revenue-after-sales"]
        zero_candidate = zero["facts"]["candidateSignals"][0]
        returns = self.inputs["returns-rise"]
        returns_candidate = returns["facts"]["candidateSignals"][0]
        conflict = self.inputs["conflicting-revenue-margin"]
        profit_candidate = next(
            value
            for value in conflict["facts"]["candidateSignals"]
            if value["theme"] == "PROFITABILITY"
        )
        accessory = self.inputs["accessory-gap"]
        accessory_candidate = accessory["facts"]["candidateSignals"][0]
        month_end = self.inputs["month-end-recovery"]
        month_end_candidate = month_end["facts"]["candidateSignals"][0]

        self.assertEqual(
            "Чистая выручка равна нулю после ненулевого значения "
            "прошлого периода.",
            EVALUATE.candidate_narrative(
                zero_candidate, zero
            )[1],
        )
        self.assertEqual(
            "Чистая выручка (продажи за вычетом возвратов) существенно "
            "снизилась относительно прошлого периода.",
            EVALUATE.candidate_narrative(
                returns_candidate, returns
            )[1],
        )
        self.assertEqual(
            "Валовая прибыль и маржинальность существенно снизились "
            "относительно прошлого периода.",
            EVALUATE.candidate_narrative(
                profit_candidate, conflict
            )[1],
        )
        self.assertEqual(
            [
                "STORE.GROSS_PROFIT.CURRENT",
                "STORE.MARGIN_PERCENT.CURRENT",
            ],
            EVALUATE.candidate_evidence_refs(
                profit_candidate, conflict
            ),
        )
        self.assertEqual(
            "Выручка и доля категории «Кабели и зарядные устройства» "
            "существенно снизились.",
            EVALUATE.candidate_narrative(
                accessory_candidate, accessory
            )[1],
        )

        self.assertEqual(
            "Завершившийся период закрыт существенно ниже целевого "
            "уровня выполнения плана.",
            EVALUATE.candidate_narrative(
                month_end_candidate, month_end
            )[1],
        )

    def test_v19_allows_identical_mandatory_employee_headlines(self):
        case_id = "team-tie-no-leader"
        case = case_by_id(self.dataset, case_id)
        output = valid_v3_output(self.inputs[case_id])
        for summary in output["summaryBlocks"]:
            if summary["scope"] == "EMPLOYEE":
                summary["text"] = (
                    "По сотруднику нет отдельного существенного изменения."
                )

        failures, metrics = EVALUATE.validate_response(
            self.v3_validator,
            self.dataset,
            case,
            self.inputs[case_id],
            output,
            "v19",
        )

        self.assertFalse(any(
            "duplicate narratives" in failure for failure in failures
        ))
        self.assertEqual(0, metrics["duplicateNarratives"])

    def test_v19_structured_transport_builds_backend_relationships(self):
        case_id = "team-most-improved"
        case = case_by_id(self.dataset, case_id)
        payload = self.inputs[case_id]
        canonical = valid_v3_output(payload)
        team = next(
            summary
            for summary in canonical["summaryBlocks"]
            if summary["scope"] == "TEAM"
        )
        transport = {
            key: copy.deepcopy(value)
            for key, value in canonical.items()
            if key not in {"employees", "summaryBlocks", "dataLimitations"}
        }
        transport["teamOverview"] = {
            "text": team["text"],
            "evidenceRefs": team["evidenceRefs"],
        }
        transport["employeeHeadlines"] = {
            summary["employeeRef"]: {
                "text": summary["text"],
                "evidenceRefs": summary["evidenceRefs"],
            }
            for summary in canonical["summaryBlocks"]
            if summary["scope"] == "EMPLOYEE"
        }
        transport["supportingSummaries"] = []
        transport["teamRelationships"] = []

        failures, metrics = EVALUATE.validate_response(
            self.v3_validator,
            self.dataset,
            case,
            payload,
            transport,
            "v19",
        )

        self.assertFalse(any(
            "teamRelationship" in failure for failure in failures
        ))
        normalized = EVALUATE.backend_normalize_response(transport, payload)
        self.assertEqual(1, metrics["teamRelationships"])
        self.assertEqual(
            "MOST_IMPROVED",
            normalized["teamRelationships"][0]["type"],
        )
        self.assertEqual(
            ["E01"],
            normalized["teamRelationships"][0]["sourceEmployeeRefs"],
        )
        self.assertEqual(
            [],
            normalized["teamRelationships"][0]["targetEmployeeRefs"],
        )

    def test_dataset_is_valid_and_has_required_coverage(self):
        self.assertEqual([], self.dataset_failures)
        self.assertEqual(26, len(self.dataset["cases"]))
        self.assertEqual(set(self.inputs), {
            case["id"] for case in self.dataset["cases"]
        })
        tags = {
            tag
            for case in self.dataset["cases"]
            for tag in case["tags"]
        }
        self.assertTrue(
            set(self.dataset["coverage"]["requiredTags"]).issubset(tags)
        )

    def test_action_policy_rejects_inverted_similarity_thresholds(self):
        dataset = copy.deepcopy(self.dataset)
        dataset["actionQuality"]["nonSpecificNearDuplicateSimilarity"] = 0.9
        dataset["actionQuality"]["nearDuplicateSimilarity"] = 0.7

        failures, _ = EVALUATE.validate_dataset(
            ROOT,
            DATASET_PATH,
            dataset,
        )

        self.assertTrue(any(
            "non-specific near-duplicate threshold" in failure
            for failure in failures
        ))

    def test_generated_inputs_are_deterministic_and_case_specific(self):
        first = EVALUATE.build_input(
            self.dataset,
            case_by_id(self.dataset, "stable-week"),
        )
        second = EVALUATE.build_input(
            self.dataset,
            case_by_id(self.dataset, "stable-week"),
        )
        other = EVALUATE.build_input(
            self.dataset,
            case_by_id(self.dataset, "revenue-growth"),
        )
        self.assertEqual(first, second)
        self.assertNotEqual(
            first["snapshot"]["snapshotRef"],
            other["snapshot"]["snapshotRef"],
        )
        self.assertNotEqual(
            first["snapshot"]["factsHash"],
            other["snapshot"]["factsHash"],
        )

    def test_input_rejects_non_production_fact_and_candidate_references(self):
        stable = case_by_id(self.dataset, "stable-week")
        payload = copy.deepcopy(self.inputs[stable["id"]])
        payload["facts"]["store"][0]["evidenceRef"] = "STORE.REVENUE"
        failures = EVALUATE.input_semantic_failures(
            stable["id"], payload, stable
        )
        self.assertTrue(any(
            "does not match production metric" in failure
            for failure in failures
        ))

        growth = case_by_id(self.dataset, "revenue-growth")
        payload = copy.deepcopy(self.inputs[growth["id"]])
        payload["facts"]["candidateSignals"][0]["candidateRef"] = "C01"
        failures = EVALUATE.input_semantic_failures(
            growth["id"], payload, growth
        )
        self.assertTrue(any(
            "non-production candidateRef format" in failure
            for failure in failures
        ))

    def test_input_periods_must_match_production_week_boundaries(self):
        stable = case_by_id(self.dataset, "stable-week")
        payload = copy.deepcopy(self.inputs[stable["id"]])
        payload["snapshot"]["period"] = {
            "start": "2026-08-04",
            "end": "2026-08-10",
        }
        failures = EVALUATE.input_semantic_failures(
            stable["id"], payload, stable
        )
        self.assertTrue(any(
            "period must be a Monday-Sunday week" in failure
            for failure in failures
        ))

    def test_relationship_candidate_shape_is_backend_owned(self):
        case = case_by_id(self.dataset, "team-unique-leader")
        payload = copy.deepcopy(self.inputs[case["id"]])
        payload["facts"]["candidateSignals"][0]["targetEmployeeRefs"] = [
            "E02"
        ]
        failures = EVALUATE.input_semantic_failures(
            case["id"], payload, case
        )
        self.assertTrue(any(
            "has invalid shape" in failure for failure in failures
        ))

    def test_exports_every_provider_input(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            EVALUATE.export_inputs(target, self.inputs)
            exported = sorted(target.glob("*.json"))
            self.assertEqual(len(self.dataset["cases"]), len(exported))
            sample = json.loads(
                (target / "stable-week.json").read_text(encoding="utf-8")
            )
            self.assertEqual(self.inputs["stable-week"], sample)

    def test_grounded_stable_response_passes(self):
        output = valid_output(self.inputs["stable-week"])
        self.assertEqual([], self.validate("stable-week", output))

    def test_unknown_evidence_digits_and_identifier_leak_fail(self):
        output = valid_output(self.inputs["stable-week"])
        output["summaryBlocks"][0]["text"] = "E01 показал результат 123."
        output["summaryBlocks"][0]["evidenceRefs"] = ["UNKNOWN.EVIDENCE"]
        failures = self.validate("stable-week", output)
        joined = "\n".join(failures)
        self.assertIn("unavailable evidence", joined)
        self.assertIn("contains digits", joined)
        self.assertIn("technical identifiers", joined)

    def test_workload_summary_requires_direct_workload_evidence(self):
        case_id = "accessory-gap"
        output = valid_output(self.inputs[case_id])
        output["summaryBlocks"].append({
            "scope": "EMPLOYEE",
            "employeeRef": "E01",
            "section": "WORKLOAD",
            "categoryCode": None,
            "text": "Данные о рабочей нагрузке отсутствуют.",
            "evidenceRefs": ["EMP:E01.NET_REVENUE.CURRENT"],
        })
        failures = self.validate(case_id, output)
        self.assertTrue(any(
            "workload summary lacks workload evidence" in failure
            for failure in failures
        ))

    def test_profitability_narrative_requires_profitability_evidence(self):
        case_id = "accessory-gap"
        output = valid_output(self.inputs[case_id])
        output["summaryBlocks"][0]["text"] = (
            "\u041f\u0430\u0434\u0435\u043d\u0438\u0435 \u0432\u044b\u0440\u0443\u0447\u043a\u0438 "
            "\u0441\u043d\u0438\u0436\u0430\u0435\u0442 \u043f\u0440\u0438\u0431\u044b\u043b\u044c\u043d\u043e\u0441\u0442\u044c."
        )
        output["summaryBlocks"][0]["evidenceRefs"] = [
            "STORE.NET_REVENUE.CURRENT"
        ]

        failures = self.validate(case_id, output)

        self.assertTrue(any(
            "unsupported PROFITABILITY narrative dimension" in failure
            and "$.summaryBlocks[0]" in failure
            for failure in failures
        ))

    def test_income_return_is_profitability_not_revenue(self):
        case_id = "conflicting-revenue-margin"
        output = valid_output(self.inputs[case_id])
        output["summaryBlocks"][0]["text"] = "Доходность снизилась."
        output["summaryBlocks"][0]["evidenceRefs"] = [
            "STORE.MARGIN_PERCENT.CURRENT"
        ]

        failures = self.validate(case_id, output)
        self.assertFalse(any(
            "unsupported REVENUE narrative dimension" in failure
            or "unsupported PROFITABILITY narrative dimension" in failure
            for failure in failures
        ))

    def test_inflected_income_is_supported_by_revenue_evidence(self):
        case_id = "conflicting-revenue-margin"
        output = valid_output(self.inputs[case_id])
        output["summaryBlocks"][0]["text"] = "Доходы выросли."
        output["summaryBlocks"][0]["evidenceRefs"] = [
            "STORE.NET_REVENUE.CURRENT"
        ]

        failures = self.validate(case_id, output)
        self.assertFalse(any(
            "unsupported REVENUE narrative dimension" in failure
            or "unsupported PROFITABILITY narrative dimension" in failure
            for failure in failures
        ))

    def test_v19_action_count_cannot_exceed_candidate_count(self):
        case_id = "accessory-gap"
        case = case_by_id(self.dataset, case_id)
        output = valid_output(self.inputs[case_id])
        action = store_action(
            self.inputs[case_id],
            "INVESTIGATION",
            "Check revenue evidence",
            "Inspect revenue documents and record the findings.",
        )
        output["actions"] = [action, copy.deepcopy(action)]

        failures, _ = EVALUATE.validate_response(
            self.validator,
            self.dataset,
            case,
            self.inputs[case_id],
            output,
            "v19",
        )

        self.assertTrue(any(
            "action count exceeds non-relationship candidate count"
            in failure
            for failure in failures
        ))

    def test_non_specific_and_near_duplicate_actions_fail(self):
        case_id = "stable-week"
        output = valid_output(self.inputs[case_id])
        output["actions"] = [
            store_action(
                self.inputs[case_id],
                "INVESTIGATION",
                "Анализ ситуации с выручкой в категории кабелей",
                (
                    "Проанализировать причины снижения выручки в категории "
                    "кабелей и разработать меры по восстановлению показателей."
                ),
            ),
            store_action(
                self.inputs[case_id],
                "PROCESS_REVIEW",
                "Фокус на категории кабелей",
                (
                    "Сосредоточить внимание на восстановлении доли и объёма "
                    "выручки в категории кабелей."
                ),
            ),
        ]

        failures = self.validate(case_id, output)
        self.assertEqual(2, sum(
            "action lacks an observable operation" in failure
            for failure in failures
        ))
        self.assertTrue(any(
            "actions are near-duplicates" in failure
            for failure in failures
        ))
        metrics = EVALUATE.response_metrics(
            output,
            case_by_id(self.dataset, case_id),
            self.dataset,
        )
        self.assertEqual(2, metrics["nonSpecificActions"])
        self.assertEqual(1, metrics["nearDuplicateActions"])

    def test_boilerplate_action_is_non_specific_despite_recording_verb(self):
        case_id = "stable-week"
        output = valid_output(self.inputs[case_id])
        output["actions"] = [store_action(
            self.inputs[case_id],
            "INVESTIGATION",
            "Анализ ситуации с выручкой",
            (
                "Проанализировать причины снижения выручки, "
                "зафиксировать выявленные проблемы и разработать меры "
                "по восстановлению показателей."
            ),
        )]

        failures = self.validate(case_id, output)
        self.assertTrue(any(
            "action lacks an observable operation at $.actions[0]"
            in failure
            for failure in failures
        ))

    def test_concrete_check_with_appended_generic_goals_is_non_specific(self):
        case_id = "stable-week"
        output = valid_output(self.inputs[case_id])
        output["actions"] = [store_action(
            self.inputs[case_id],
            "INVESTIGATION",
            "Проверка наличия и выкладки кабелей",
            (
                "Проверить наличие и выкладку по товарным позициям, "
                "проанализировать спрос и принять меры."
            ),
        )]

        result = EVALUATE.action_quality(self.dataset, output)

        self.assertEqual({0}, result["nonSpecificIndexes"])


    def test_action_quality_preserves_paths_after_malformed_item(self):
        case_id = "stable-week"
        output = valid_output(self.inputs[case_id])
        output["actions"] = [
            "schema-invalid action",
            store_action(
                self.inputs[case_id],
                "MONITORING",
                "Фокус на результате",
                "Сосредоточить внимание на результате магазина.",
            ),
        ]

        failures = self.validate(case_id, output)

        self.assertTrue(any(
            "schema $.actions[0]" in failure
            for failure in failures
        ))
        self.assertTrue(any(
            "observable operation at $.actions[1]" in failure
            for failure in failures
        ))

    def test_concrete_distinct_actions_are_not_penalized(self):
        case_id = "stable-week"
        output = valid_output(self.inputs[case_id])
        first = store_action(
            self.inputs[case_id],
            "INVESTIGATION",
            "Проверка наличия кабелей",
            (
                "Проверить наличие и остатки кабелей по товарным позициям "
                "и зафиксировать найденные пробелы."
            ),
        )
        second = store_action(
            self.inputs[case_id],
            "PROCESS_REVIEW",
            "Разбор выкладки кабелей",
            (
                "Провести разбор выкладки кабелей и зафиксировать "
                "необходимые изменения."
            ),
        )
        output["actions"] = [first]
        self.assertEqual([], self.validate(case_id, output))

        output["actions"].append(second)
        result = EVALUATE.action_quality(self.dataset, output)
        self.assertEqual(set(), result["nonSpecificIndexes"])
        self.assertEqual([], result["nearDuplicatePairs"])

    def test_insufficient_employee_cannot_receive_personal_insight(self):
        case_id = "employee-insufficient"
        output = valid_output(self.inputs[case_id])
        self.assertEqual([], self.validate(case_id, output))
        output["insights"].append({
            "scope": "EMPLOYEE",
            "employeeRef": "E01",
            "categoryCode": None,
            "kind": "OBSERVATION",
            "theme": "EMPLOYEE_PERFORMANCE",
            "candidateRef": None,
            "title": "Персональная оценка",
            "summary": "Сотруднику следует улучшить личный результат.",
            "evidenceRefs": ["EMP:E01.WORKLOAD.STATUS"],
        })
        failures = self.validate(case_id, output)
        joined = "\n".join(failures)
        self.assertIn("insights forbidden", joined)
        self.assertIn("too many insights", joined)

    def test_backend_owned_data_limitation_is_normalized_from_input(self):
        case_id = "profit-unavailable"
        output = valid_output(self.inputs[case_id])
        output["dataLimitations"] = [{
            "code": "MODEL_INVENTED",
            "scope": "TEAM",
            "employeeRef": None,
            "categoryCode": None,
            "impact": "REDUCED_CONFIDENCE",
            "affectedSections": ["RATING"],
            "summary": "Этот текст модели должен быть заменён.",
            "evidenceRefs": [],
        }]
        self.assertEqual([], self.validate(case_id, output))
        normalized = EVALUATE.backend_normalize_response(
            output, self.inputs[case_id]
        )
        self.assertEqual("COST_DATA_INCOMPLETE",
                         normalized["dataLimitations"][0]["code"])
        self.assertEqual(["PROFITABILITY"],
                         normalized["dataLimitations"][0]["affectedSections"])

    def test_team_relationship_requires_exact_candidate(self):
        case_id = "team-unique-leader"
        output = valid_output(self.inputs[case_id])
        output["teamRelationships"].append({
            "type": "COMPETENCY_LEADER",
            "competencyCode": "CATEGORY:SETUP_SERVICE",
            "sourceEmployeeRefs": ["E01"],
            "targetEmployeeRefs": [],
            "summary": "Лидер по услугам может поделиться наблюдаемой практикой.",
            "evidenceRefs": [
                "EMP:E01.CATEGORY:SETUP_SERVICE.NET_REVENUE.CURRENT",
                "EMP:E02.CATEGORY:SETUP_SERVICE.NET_REVENUE.CURRENT",
                "TEAM.CATEGORY:SETUP_SERVICE.NET_REVENUE.MEDIAN",
            ],
        })
        output["teamRelationships"].append({
            "type": "LEARNING_OPPORTUNITY",
            "competencyCode": "CATEGORY:SETUP_SERVICE",
            "sourceEmployeeRefs": ["E01"],
            "targetEmployeeRefs": ["E03"],
            "summary": "Наблюдаемую практику можно обсудить с коллегой.",
            "evidenceRefs": [
                "EMP:E01.CATEGORY:SETUP_SERVICE.NET_REVENUE.CURRENT",
                "TEAM.CATEGORY:SETUP_SERVICE.NET_REVENUE.MEDIAN",
                "EMP:E03.CATEGORY:SETUP_SERVICE.NET_REVENUE.CURRENT",
            ],
        })
        self.assertEqual([], self.validate(case_id, output))
        output["teamRelationships"][0]["sourceEmployeeRefs"] = ["E02"]
        failures = self.validate(case_id, output)
        joined = "\n".join(failures)
        self.assertIn("missing team relationship", joined)
        self.assertIn("not backed by an exact input candidate", joined)

    def test_near_duplicate_headline_and_directive_insight_fail(self):
        case_id = "accessory-gap"
        output = valid_output(self.inputs[case_id])
        evidence = [
            "STORE.CATEGORY:CHARGER_CABLE.NET_REVENUE.CURRENT",
            "STORE.CATEGORY:CHARGER_CABLE.REVENUE_SHARE_PERCENT.CURRENT",
        ]
        output["summaryBlocks"][0].update({
            "categoryCode": "CHARGER_CABLE",
            "text": (
                "Снижение выручки и доли в выручке по категории "
                "«Кабели и зарядные устройства»"
            ),
            "evidenceRefs": evidence,
        })
        output["insights"] = [{
            "scope": "STORE",
            "employeeRef": None,
            "categoryCode": "CHARGER_CABLE",
            "kind": "RISK",
            "theme": "CATEGORY_MIX",
            "candidateRef": "C001",
            "title": (
                "Снижение выручки по категории "
                "«Кабели и зарядные устройства»"
            ),
            "summary": (
                "Выручка и доля снизились. Необходимо выявить причины "
                "снижения и принять меры."
            ),
            "evidenceRefs": evidence,
        }]

        failures = self.validate(case_id, output)
        self.assertTrue(any(
            "headline and insight title are near-duplicates" in failure
            for failure in failures
        ))
        self.assertTrue(any(
            "insight contains a management directive" in failure
            for failure in failures
        ))
        metrics = EVALUATE.response_metrics(
            output, case_by_id(self.dataset, case_id), self.dataset
        )
        self.assertEqual(1, metrics["nearDuplicateNarratives"])
        self.assertEqual(1, metrics["directiveInsights"])

    def test_near_duplicate_primary_signal_and_insight_fail(self):
        output = {
            "primarySignal": {
                "text": "Снижение выручки по категории кабелей"
            },
            "summaryBlocks": [],
            "insights": [{
                "scope": "STORE",
                "title": "Снижение выручки по категории кабелей",
                "summary": "Категория требует внимания.",
            }],
            "teamRelationships": [],
        }

        result = EVALUATE.narrative_quality(self.dataset, output)

        self.assertEqual(
            1,
            len(result["nearDuplicatePrimaryInsightPairs"]),
        )
        metrics = EVALUATE.response_metrics(
            output,
            case_by_id(self.dataset, "stable-week"),
            self.dataset,
        )
        self.assertEqual(1, metrics["nearDuplicateNarratives"])

    def test_near_duplicate_primary_signal_and_team_overview_fail(self):
        case_id = "accessory-gap"
        case = case_by_id(self.dataset, case_id)
        output = valid_v3_output(self.inputs[case_id])
        team_overview = next(
            summary
            for summary in output["summaryBlocks"]
            if summary["scope"] == "TEAM"
            and summary["section"] == "TEAM_OVERVIEW"
        )
        output["primarySignal"]["text"] = (
            "Снизилась выручка и доля в общем доходе магазина по "
            "категории «Кабели и зарядные устройства»."
        )
        team_overview["text"] = (
            "За отчётный период наблюдается снижение выручки и доли "
            "в общем доходе магазина по категории «Кабели и зарядные "
            "устройства»."
        )

        failures, metrics = EVALUATE.validate_response(
            self.v3_validator,
            self.dataset,
            case,
            self.inputs[case_id],
            output,
            "v19",
        )

        self.assertTrue(any(
            "primarySignal and TEAM overview are near-duplicates" in failure
            for failure in failures
        ))
        self.assertEqual(
            1, metrics["nearDuplicatePrimaryTeamOverviews"]
        )

    def test_v19_team_overview_rejects_non_team_evidence(self):
        case_id = "accessory-gap"
        case = case_by_id(self.dataset, case_id)
        output = valid_v3_output(self.inputs[case_id])
        team_overview = next(
            summary
            for summary in output["summaryBlocks"]
            if summary["scope"] == "TEAM"
            and summary["section"] == "TEAM_OVERVIEW"
        )
        team_overview["evidenceRefs"] = list(
            output["primarySignal"]["evidenceRefs"]
        )

        failures, metrics = EVALUATE.validate_response(
            self.v3_validator,
            self.dataset,
            case,
            self.inputs[case_id],
            output,
            "v19",
        )

        self.assertTrue(any(
            "TEAM overview cites non-TEAM evidence" in failure
            for failure in failures
        ))
        self.assertFalse(any(
            "primarySignal and TEAM overview are near-duplicates" in failure
            for failure in failures
        ))
        self.assertEqual(
            0, metrics["nearDuplicatePrimaryTeamOverviews"]
        )

    def test_employee_insight_directive_fails(self):
        case_id = "employee-improvement"
        output = valid_output(self.inputs[case_id])
        output["insights"] = [{
            "scope": "EMPLOYEE",
            "employeeRef": "E01",
            "categoryCode": None,
            "kind": "OPPORTUNITY",
            "theme": "EMPLOYEE_PERFORMANCE",
            "candidateRef": "C001",
            "title": "Положительная динамика сотрудника",
            "summary": (
                "Результат вырос. Нужно закрепить положительную динамику."
            ),
            "evidenceRefs": [
                "EMP:E01.NET_REVENUE.CURRENT"
            ],
        }]

        failures = self.validate(case_id, output)

        self.assertTrue(any(
            "insight contains a management directive" in failure
            for failure in failures
        ))
        metrics = EVALUATE.response_metrics(
            output,
            case_by_id(self.dataset, case_id),
            self.dataset,
        )
        self.assertEqual(1, metrics["directiveInsights"])

    def test_contained_title_and_unsupported_possible_causes_fail(self):
        case_id = "accessory-gap"
        output = valid_output(self.inputs[case_id])
        evidence = [
            "STORE.CATEGORY:CHARGER_CABLE.NET_REVENUE.CURRENT",
            "STORE.CATEGORY:CHARGER_CABLE.REVENUE_SHARE_PERCENT.CURRENT",
        ]
        output["summaryBlocks"][0].update({
            "categoryCode": "CHARGER_CABLE",
            "text": (
                "Снижение выручки в категории «Кабели и зарядные "
                "устройства» может указывать на проблемы с ассортиментом "
                "или выкладкой товаров"
            ),
            "evidenceRefs": evidence,
        })
        output["insights"] = [{
            "scope": "STORE",
            "employeeRef": None,
            "categoryCode": "CHARGER_CABLE",
            "kind": "RISK",
            "theme": "CATEGORY_MIX",
            "candidateRef": "C001",
            "title": (
                "Снижение выручки в категории "
                "«Кабели и зарядные устройства»"
            ),
            "summary": (
                "Выручка и доля снизились. Это может указывать на "
                "проблемы с ассортиментом, выкладкой или спросом."
            ),
            "evidenceRefs": evidence,
        }]

        failures = self.validate(case_id, output)

        self.assertTrue(any(
            "headline and insight title are near-duplicates" in failure
            and "containment=1.0000" in failure
            for failure in failures
        ))
        self.assertEqual(
            2,
            sum(
                "narrative states an unsupported possible cause" in failure
                for failure in failures
            ),
        )
        metrics = EVALUATE.response_metrics(
            output, case_by_id(self.dataset, case_id), self.dataset
        )
        self.assertEqual(1, metrics["nearDuplicateNarratives"])
        self.assertEqual(2, metrics["unsupportedCauseNarratives"])

    def test_hypothesis_insight_may_state_a_possible_cause(self):
        result = EVALUATE.narrative_quality(self.dataset, {
            "summaryBlocks": [],
            "insights": [{
                "kind": "HYPOTHESIS",
                "title": "Возможная причина снижения",
                "summary": "Снижение может быть связано с наличием товара.",
            }],
            "teamRelationships": [],
        })

        self.assertEqual([], result["unsupportedCauseItems"])

    def test_candidate_owned_fields_are_backend_normalized(self):
        case_id = "revenue-growth"
        output = valid_output(self.inputs[case_id])
        output["insights"].append({
            "scope": "STORE",
            "employeeRef": None,
            "categoryCode": None,
            "kind": "OPPORTUNITY",
            "theme": "REVENUE_DYNAMICS",
            "candidateRef": "C001",
            "title": "Рост выручки",
            "summary": "Чистая выручка заметно выросла к прошлой неделе.",
            "evidenceRefs": ["STORE.NET_REVENUE.CURRENT"],
        })
        self.assertEqual([], self.validate(case_id, output))
        output["insights"][0]["kind"] = "RISK"
        self.assertEqual([], self.validate(case_id, output))

    def test_require_responses_reports_full_matrix(self):
        with tempfile.TemporaryDirectory() as directory:
            failures, report = EVALUATE.evaluate_dataset_responses(
                ROOT,
                self.dataset,
                self.inputs,
                Path(directory),
                True,
            )
        expected = len(self.dataset["cases"]) * len(
            self.dataset["configurations"]
        )
        self.assertEqual(expected, len(failures))
        self.assertEqual(0, report["evaluatedResponses"])
        self.assertFalse(report["passed"])
        for configuration in self.dataset["configurations"]:
            metrics = report["automaticMetrics"][configuration["id"]]
            self.assertEqual(len(self.dataset["cases"]), metrics["missingResponses"])
            self.assertEqual(0, metrics["evaluatedResponses"])
            self.assertEqual(0, metrics["passedResponses"])
            self.assertEqual(0, metrics["violationCount"])
            self.assertIsNone(metrics["passRate"])

    def test_legacy_manifest_still_passes(self):
        manifest = EVALUATE.load_json(
            ROOT / "scripts" / "llm-eval" / "manifest.example.json"
        )
        failures, count = EVALUATE.evaluate_legacy(ROOT, manifest)
        self.assertEqual([], failures)
        self.assertEqual(2, count)


if __name__ == "__main__":
    unittest.main()

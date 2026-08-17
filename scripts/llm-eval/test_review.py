#!/usr/bin/env python3

from __future__ import annotations

import copy
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
EVALUATE_PATH = ROOT / "scripts" / "llm-eval" / "evaluate.py"
REVIEW_PATH = ROOT / "scripts" / "llm-eval" / "review.py"

EVALUATE_SPEC = importlib.util.spec_from_file_location("evaluate", EVALUATE_PATH)
EVALUATE = importlib.util.module_from_spec(EVALUATE_SPEC)
assert EVALUATE_SPEC.loader is not None
EVALUATE_SPEC.loader.exec_module(EVALUATE)
sys.modules["evaluate"] = EVALUATE

REVIEW_SPEC = importlib.util.spec_from_file_location("llm_review", REVIEW_PATH)
REVIEW = importlib.util.module_from_spec(REVIEW_SPEC)
assert REVIEW_SPEC.loader is not None
REVIEW_SPEC.loader.exec_module(REVIEW)


def sample_dataset(case_count: int = 4) -> dict:
    return {
        "version": 2,
        "configurations": [
            {"id": "v4", "label": "baseline"},
            {"id": "v12", "label": "candidate"},
        ],
        "humanRubric": {
            "scale": {"min": 1, "max": 5, "passAverage": 4},
            "dimensions": [
                {"id": "accuracy", "question": "accuracy?"},
                {"id": "usefulness", "question": "usefulness?"},
                {"id": "priority", "question": "priority?"},
                {"id": "actions", "question": "actions?"},
                {"id": "tone", "question": "tone?"},
            ],
            "criticalErrors": ["Invented fact", "Wrong direction"],
        },
        "cases": [
            {
                "id": f"case-{index}",
                "title": f"Case {index}",
                "tags": ["test"],
                "expectations": {
                    "requiredFindings": ["Required meaning"],
                    "acceptableFindings": ["Optional meaning"],
                    "forbiddenFindings": ["Forbidden meaning"],
                },
            }
            for index in range(1, case_count + 1)
        ],
    }


def automatic_report() -> dict:
    return {
        "caseCount": 4,
        "configurationCount": 2,
        "evaluatedResponses": 8,
        "passed": True,
        "automaticMetrics": {
            configuration: {
                "expectedResponses": 4,
                "evaluatedResponses": 4,
                "passedResponses": 4,
                "missingResponses": 0,
                "violationCount": 0,
                "passRate": 1.0,
                "averages": {
                    "requiredCandidateCoverage": 1.0,
                    "duplicateNarratives": 0.0,
                    "nearDuplicateNarratives": 0.0,
                    "nearDuplicatePrimaryTeamOverviews": 0.0,
                    "directiveInsights": 0.0,
                    "unsupportedCauseNarratives": 0.0,
                    "nonSpecificActions": 0.0,
                    "nearDuplicateActions": 0.0,
                    "workloadBlocks": 0.0,
                    "actions": 1.0,
                },
            }
            for configuration in ("v4", "v12")
        },
    }


def complete_scores(scores: dict, mapping: dict) -> dict:
    result = copy.deepcopy(scores)
    result["reviewerId"] = "reviewer-1"
    assignments = {item["reviewId"]: item for item in mapping["assignments"]}
    for evaluation in result["evaluations"]:
        configuration = assignments[evaluation["reviewId"]]["configurationId"]
        value = 4 if configuration == "v4" else 5
        evaluation["dimensionScores"] = {
            dimension: value
            for dimension in evaluation["dimensionScores"]
        }
        for finding in evaluation["requiredFindings"]:
            finding["status"] = "COVERED"
        for finding in evaluation["forbiddenFindings"]:
            finding["status"] = "ABSENT"
        for error in evaluation["criticalErrors"]:
            error["status"] = "ABSENT"
    return result


class BlindedReviewTest(unittest.TestCase):

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.responses = Path(self.temporary.name)
        self.dataset = sample_dataset()
        self.inputs = {}
        for case in self.dataset["cases"]:
            case_id = case["id"]
            self.inputs[case_id] = {"scenario": case_id}
            target = self.responses / case_id
            target.mkdir(parents=True)
            for configuration in ("v4", "v12"):
                (target / f"{configuration}.json").write_text(
                    json.dumps({"summary": f"{case_id}-{configuration}"}),
                    encoding="utf-8",
                )
        self.packet, self.mapping, self.scores = REVIEW.build_review_artifacts(
            self.dataset,
            self.inputs,
            self.responses,
            "a" * 64,
            automatic_report(),
        )

    def tearDown(self):
        self.temporary.cleanup()

    def test_review_packet_uses_backend_canonical_response(self):
        manifest = ROOT / "scripts/llm-eval/dataset-v2.json"
        dataset = EVALUATE.load_json(manifest)
        failures, inputs = EVALUATE.validate_dataset(
            ROOT, manifest, dataset
        )
        self.assertEqual([], failures)
        response = {
            "actions": [],
            "insights": [],
            "teamRelationships": [],
            "primarySignal": None,
            "supportingSummaries": [],
            "teamOverview": {
                "text": "Team comparison is limited.",
                "evidenceRefs": ["TEAM.RATING.ELIGIBLE_COUNT"],
            },
            "backendEmployeeHeadlines": True,
        }

        canonical = REVIEW.canonical_review_response(
            response, inputs["stable-week"]
        )

        self.assertNotIn("backendEmployeeHeadlines", canonical)
        self.assertNotIn("teamOverview", canonical)
        self.assertEqual(1, len(canonical["employees"]))
        self.assertEqual(
            ["RESULT", "TEAM_OVERVIEW", "HEADLINE"],
            [summary["section"] for summary in canonical["summaryBlocks"]],
        )

    def test_packet_is_deterministic_blinded_and_counterbalanced(self):
        packet_again, mapping_again, scores_again = REVIEW.build_review_artifacts(
            self.dataset,
            self.inputs,
            self.responses,
            "a" * 64,
            automatic_report(),
        )
        self.assertEqual(self.packet, packet_again)
        self.assertEqual(self.mapping, mapping_again)
        self.assertEqual(self.scores, scores_again)
        serialized_packet = json.dumps(self.packet, sort_keys=True)
        self.assertNotIn('"v4"', serialized_packet)
        self.assertNotIn('"v12"', serialized_packet)
        v4_aliases = [
            item["alias"]
            for item in self.mapping["assignments"]
            if item["configurationId"] == "v4"
        ]
        self.assertEqual(v4_aliases.count("A"), 2)
        self.assertEqual(v4_aliases.count("B"), 2)

    def test_completed_scores_require_every_binding_and_assessment(self):
        completed = complete_scores(self.scores, self.mapping)
        self.assertEqual(
            REVIEW.validate_completed_scores(
                ROOT, self.packet, self.mapping, completed
            ),
            [],
        )

        incomplete = copy.deepcopy(completed)
        first_dimension = next(iter(incomplete["evaluations"][0]["dimensionScores"]))
        incomplete["evaluations"][0]["dimensionScores"][first_dimension] = None
        failures = REVIEW.validate_completed_scores(
            ROOT, self.packet, self.mapping, incomplete
        )
        self.assertTrue(any("must be an integer" in failure for failure in failures))

        incomplete = copy.deepcopy(completed)
        incomplete["evaluations"][0]["requiredFindings"][0]["status"] = "UNSCORED"
        failures = REVIEW.validate_completed_scores(
            ROOT, self.packet, self.mapping, incomplete
        )
        self.assertTrue(any("assessment is incomplete" in failure for failure in failures))

        tampered_mapping = copy.deepcopy(self.mapping)
        tampered_mapping["assignments"][0]["configurationId"] = "other"
        failures = REVIEW.validate_completed_scores(
            ROOT, self.packet, tampered_mapping, completed
        )
        self.assertTrue(any("assignmentSha256" in failure for failure in failures))

    def test_response_change_is_detected_after_packet_creation(self):
        self.assertEqual(
            REVIEW.verify_artifact_integrity(
                self.packet, self.mapping, self.responses
            ),
            [],
        )
        first = self.mapping["assignments"][0]
        (self.responses / first["responseRelativePath"]).write_text(
            '{"summary":"changed"}',
            encoding="utf-8",
        )
        failures = REVIEW.verify_artifact_integrity(
            self.packet, self.mapping, self.responses
        )
        self.assertTrue(any("response hash differs" in failure for failure in failures))

    def test_integrity_compares_packet_with_canonical_response(self):
        manifest = ROOT / "scripts/llm-eval/dataset-v2.json"
        dataset = EVALUATE.load_json(manifest)
        failures, inputs = EVALUATE.validate_dataset(
            ROOT, manifest, dataset
        )
        self.assertEqual([], failures)
        case_id = "stable-week"
        response_root = self.responses / "canonical"
        response_dir = response_root / case_id
        response_dir.mkdir(parents=True)
        raw_response = {
            "actions": [],
            "insights": [],
            "teamRelationships": [],
            "primarySignal": None,
            "supportingSummaries": [],
            "teamOverview": {
                "text": "Team comparison is limited.",
                "evidenceRefs": ["TEAM.RATING.ELIGIBLE_COUNT"],
            },
            "backendEmployeeHeadlines": True,
        }
        for configuration in ("v4", "v19"):
            (response_dir / f"{configuration}.json").write_text(
                json.dumps(raw_response),
                encoding="utf-8",
            )
        review_dataset = copy.deepcopy(dataset)
        review_dataset["cases"] = [
            case for case in dataset["cases"] if case["id"] == case_id
        ]
        review_dataset["configurations"] = [
            configuration
            for configuration in dataset["configurations"]
            if configuration["id"] in {"v4", "v19"}
        ]
        metrics = copy.deepcopy(
            automatic_report()["automaticMetrics"]["v4"]
        )
        metrics["expectedResponses"] = 1
        metrics["evaluatedResponses"] = 1
        report = {
            "caseCount": 1,
            "configurationCount": 2,
            "evaluatedResponses": 2,
            "passed": True,
            "automaticMetrics": {
                configuration: copy.deepcopy(metrics)
                for configuration in ("v4", "v19")
            },
        }
        packet, mapping, _ = REVIEW.build_review_artifacts(
            review_dataset,
            {case_id: inputs[case_id]},
            response_root,
            "b" * 64,
            report,
        )

        self.assertEqual(
            [],
            REVIEW.verify_artifact_integrity(
                packet, mapping, response_root
            ),
        )

    def test_artifact_writer_refuses_to_overwrite_existing_file(self):
        target = self.responses / "artifact.json"
        REVIEW.write_json(target, {"original": True})
        with self.assertRaises(FileExistsError):
            REVIEW.write_json(target, {"replacement": True})
        self.assertEqual(
            json.loads(target.read_text(encoding="utf-8")),
            {"original": True},
        )

    def test_candidate_passes_only_when_manual_and_non_regression_gates_pass(self):
        completed = complete_scores(self.scores, self.mapping)
        manual = REVIEW.manual_report(self.packet, self.mapping, completed)
        decision = REVIEW.build_decision(
            self.dataset,
            manual,
            automatic_report(),
            "v4",
            "v12",
        )
        self.assertEqual(decision["code"], "CANDIDATE_ELIGIBLE_FOR_CANARY")

        action_regression = automatic_report()
        action_regression["automaticMetrics"]["v12"]["averages"][
            "nonSpecificActions"
        ] = 0.25
        decision = REVIEW.build_decision(
            self.dataset,
            manual,
            action_regression,
            "v4",
            "v12",
        )
        self.assertEqual(
            decision["code"],
            "KEEP_BASELINE_AND_REVISE_CANDIDATE",
        )
        specificity = next(
            comparison
            for comparison in decision["comparisons"]
            if comparison["id"] == "action-specificity-non-inferior"
        )
        self.assertFalse(specificity["passed"])

        cause_regression = automatic_report()
        cause_regression["automaticMetrics"]["v12"]["averages"][
            "unsupportedCauseNarratives"
        ] = 0.25
        decision = REVIEW.build_decision(
            self.dataset,
            manual,
            cause_regression,
            "v4",
            "v12",
        )
        cause_quality = next(
            comparison
            for comparison in decision["comparisons"]
            if comparison["id"] == "unsupported-causes-non-inferior"
        )
        self.assertFalse(cause_quality["passed"])

        assignments = {item["reviewId"]: item for item in self.mapping["assignments"]}
        regression = copy.deepcopy(completed)
        for evaluation in regression["evaluations"]:
            configuration = assignments[evaluation["reviewId"]]["configurationId"]
            value = 5 if configuration == "v4" else 4
            evaluation["dimensionScores"] = {
                dimension: value
                for dimension in evaluation["dimensionScores"]
            }
        manual = REVIEW.manual_report(self.packet, self.mapping, regression)
        self.assertTrue(manual["configurations"]["v12"]["eligible"])
        decision = REVIEW.build_decision(
            self.dataset,
            manual,
            automatic_report(),
            "v4",
            "v12",
        )
        self.assertEqual(decision["code"], "KEEP_BASELINE_AND_REVISE_CANDIDATE")

        critical = copy.deepcopy(completed)
        candidate_evaluation = next(
            evaluation
            for evaluation in critical["evaluations"]
            if assignments[evaluation["reviewId"]]["configurationId"] == "v12"
        )
        candidate_evaluation["criticalErrors"][0]["status"] = "PRESENT"
        manual = REVIEW.manual_report(self.packet, self.mapping, critical)
        decision = REVIEW.build_decision(
            self.dataset,
            manual,
            automatic_report(),
            "v4",
            "v12",
        )
        self.assertEqual(decision["code"], "KEEP_BASELINE_AND_REVISE_CANDIDATE")
        self.assertFalse(decision["candidateEligibleForCanary"])


    def test_baseline_violations_do_not_block_candidate_review(self):
        report = automatic_report()
        report["passed"] = False
        report["automaticMetrics"]["v4"].update({
            "passedResponses": 3,
            "violationCount": 1,
            "passRate": 0.75,
        })

        failures = REVIEW.review_eligibility_failures(
            self.dataset,
            ["case-1/v4: known baseline narrative violation"],
            report,
            "v4",
            "v12",
        )

        self.assertEqual(failures, [])
        self.assertEqual(
            report["reviewEligibility"]["baselineViolationCount"],
            1,
        )
        self.assertTrue(
            report["reviewEligibility"]["candidateEligibleForBlindedReview"]
        )

    def test_candidate_violation_blocks_blinded_review(self):
        report = automatic_report()
        report["passed"] = False
        report["automaticMetrics"]["v12"].update({
            "passedResponses": 3,
            "violationCount": 1,
            "passRate": 0.75,
        })

        failures = REVIEW.review_eligibility_failures(
            self.dataset,
            ["case-1/v12: candidate narrative violation"],
            report,
            "v4",
            "v12",
        )

        self.assertTrue(any("case-1/v12" in failure for failure in failures))
        self.assertTrue(report["reviewEligibility"]["matrixComplete"])
        self.assertFalse(
            report["reviewEligibility"]["candidateEligibleForBlindedReview"]
        )

    def test_incomplete_or_unreadable_matrix_blocks_review(self):
        incomplete = automatic_report()
        incomplete["evaluatedResponses"] = 7
        incomplete["automaticMetrics"]["v4"].update({
            "evaluatedResponses": 3,
            "passedResponses": 3,
            "missingResponses": 1,
        })
        failures = REVIEW.review_eligibility_failures(
            self.dataset,
            ["case-1/v4: missing response case-1/v4.json"],
            incomplete,
            "v4",
            "v12",
        )
        self.assertTrue(any("missing response" in failure for failure in failures))
        self.assertFalse(incomplete["reviewEligibility"]["matrixComplete"])

        unreadable = automatic_report()
        unreadable["automaticMetrics"]["v4"].update({
            "passedResponses": 3,
            "violationCount": 1,
        })
        failures = REVIEW.review_eligibility_failures(
            self.dataset,
            ["case-1/v4: unreadable response: invalid JSON"],
            unreadable,
            "v4",
            "v12",
        )
        self.assertTrue(any("unreadable response" in failure for failure in failures))
        self.assertFalse(unreadable["reviewEligibility"]["matrixComplete"])


if __name__ == "__main__":
    unittest.main()

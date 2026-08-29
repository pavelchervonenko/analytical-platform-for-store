import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts/weekly-review-ai-eval/review.py"
SPEC = importlib.util.spec_from_file_location("weekly_review_ai_review", MODULE_PATH)
REVIEW = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REVIEW)


class WeeklyReviewAiReviewTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.responses = self.root / "responses"
        self.responses.mkdir()
        self.manifest = self.root / "manifest.json"
        self.manifest.write_text(
            json.dumps(
                {
                    "version": "test-v1",
                    "minimumAverage": 4.0,
                    "cases": [
                        {
                            "id": "case-a",
                            "required": ["required"],
                            "forbidden": ["forbidden"],
                        },
                        {
                            "id": "case-b",
                            "required": ["required"],
                            "forbidden": ["forbidden"],
                        },
                    ],
                }
            ),
            encoding="utf-8",
        )
        for case_id in ("case-a", "case-b"):
            provider_input = json.dumps({"contractVersion": 1, "case": case_id})
            (self.responses / f"{case_id}.input.json").write_text(
                provider_input, encoding="utf-8"
            )
            response = json.dumps({"schemaVersion": 4, "case": case_id})
            (self.responses / f"{case_id}.json").write_text(
                response, encoding="utf-8"
            )
            (self.responses / f"{case_id}.receipt.json").write_text(
                json.dumps(
                    {
                        "corpusVersion": "test-v1",
                        "caseId": case_id,
                        "semanticValidated": True,
                        "inputHash": REVIEW.sha256(provider_input),
                        "requestHash": "a" * 64,
                    }
                ),
                encoding="utf-8",
            )

    def tearDown(self):
        self.directory.cleanup()

    def test_prepare_and_finalize_complete_integrity_checked_review(self):
        review_dir = self.root / "review"
        REVIEW.prepare(self.manifest, self.responses, review_dir)
        scores_path = review_dir / "scores.json"
        scores = json.loads(scores_path.read_text(encoding="utf-8"))
        scores["completed"] = True
        for score in scores["scores"]:
            score["dimensions"] = {dimension: 5 for dimension in REVIEW.DIMENSIONS}
            score["requiredFindingsCovered"] = True
        scores_path.write_text(json.dumps(scores), encoding="utf-8")

        report_path = self.root / "decision.json"
        self.assertEqual(
            REVIEW.finalize(
                self.manifest, self.responses, review_dir, report_path
            ),
            0,
        )
        report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(report["decision"], "CANDIDATE_ELIGIBLE_FOR_CANARY")

    def test_finalize_rejects_incomplete_or_tampered_review(self):
        review_dir = self.root / "review"
        REVIEW.prepare(self.manifest, self.responses, review_dir)
        with self.assertRaisesRegex(ValueError, "not marked completed"):
            REVIEW.finalize(
                self.manifest,
                self.responses,
                review_dir,
                self.root / "report.json",
            )

        scores_path = review_dir / "scores.json"
        scores = json.loads(scores_path.read_text(encoding="utf-8"))
        scores["completed"] = True
        for score in scores["scores"]:
            score["dimensions"] = {dimension: 5 for dimension in REVIEW.DIMENSIONS}
            score["requiredFindingsCovered"] = True
        scores_path.write_text(json.dumps(scores), encoding="utf-8")
        (self.responses / "case-a.json").write_text("{}", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "integrity mismatch"):
            REVIEW.finalize(
                self.manifest,
                self.responses,
                review_dir,
                self.root / "report.json",
            )


    def test_prepare_rejects_tampered_provider_input(self):
        input_path = self.responses / "case-a.input.json"
        input_path.write_text("{}", encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "input integrity mismatch"):
            REVIEW.prepare(
                self.manifest,
                self.responses,
                self.root / "review",
            )


if __name__ == "__main__":
    unittest.main()

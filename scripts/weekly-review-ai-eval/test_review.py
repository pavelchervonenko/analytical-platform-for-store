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
                    "promptVersion": "weekly-interpretation-v25",
                    "inputSchemaVersion": 1,
                    "selectionSchemaVersion": 1,
                    "contentSchemaVersion": 4,
                    "reviewContentKind": "RENDERED_SCHEMA4",
                    "minimumAverage": 4.0,
                    "minimumDimension": 3,
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
            provider_input = json.dumps(
                {
                    "contractVersion": 1,
                    "promptVersion": "weekly-interpretation-v25",
                    "contentSchemaVersion": 4,
                    "case": case_id,
                }
            )
            (self.responses / f"{case_id}.input.json").write_text(
                provider_input, encoding="utf-8"
            )
            provider_response = json.dumps({"selectionSchemaVersion": 1})
            (self.responses / f"{case_id}.provider.json").write_text(
                provider_response, encoding="utf-8"
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
                        "promptVersion": "weekly-interpretation-v25",
                        "inputSchemaVersion": 1,
                        "selectionSchemaVersion": 1,
                        "contentSchemaVersion": 4,
                        "reviewContentKind": "RENDERED_SCHEMA4",
                        "semanticValidated": True,
                        "inputHash": REVIEW.sha256(provider_input),
                        "providerResponseHash": REVIEW.sha256(
                            provider_response
                        ),
                        "reviewContentHash": REVIEW.sha256(response),
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


    def test_finalize_rejects_low_dimension_despite_high_average(self):
        review_dir = self.root / "review"
        REVIEW.prepare(self.manifest, self.responses, review_dir)
        scores_path = review_dir / "scores.json"
        scores = json.loads(scores_path.read_text(encoding="utf-8"))
        scores["completed"] = True
        for score in scores["scores"]:
            score["dimensions"] = {dimension: 5 for dimension in REVIEW.DIMENSIONS}
            score["dimensions"][REVIEW.DIMENSIONS[0]] = 2
            score["requiredFindingsCovered"] = True
        scores_path.write_text(json.dumps(scores), encoding="utf-8")

        report_path = self.root / "decision-low-dimension.json"
        self.assertEqual(
            REVIEW.finalize(
                self.manifest, self.responses, review_dir, report_path
            ),
            1,
        )
        report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(report["decision"], "REJECTED")

    def test_load_outputs_rejects_prompt_version_mismatch(self):
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        manifest["promptVersion"] = "weekly-interpretation-v26"
        self.manifest.write_text(json.dumps(manifest), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "prompt version mismatch"):
            REVIEW.load_outputs(self.manifest, self.responses)

    def test_load_outputs_rejects_wrong_review_content_kind(self):
        receipt_path = self.responses / "case-a.receipt.json"
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        receipt["reviewContentKind"] = "REJECTED_PROVIDER_RESPONSE"
        receipt_path.write_text(json.dumps(receipt), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "review content kind mismatch"):
            REVIEW.load_outputs(self.manifest, self.responses)

    def test_prepare_rejects_tampered_provider_response(self):
        provider_path = self.responses / "case-a.provider.json"
        provider_path.write_text("{}", encoding="utf-8")

        with self.assertRaisesRegex(
            ValueError, "provider response integrity mismatch"
        ):
            REVIEW.prepare(
                self.manifest, self.responses, self.root / "provider-review"
            )

    def test_prepare_rejects_tampered_rendered_content(self):
        content_path = self.responses / "case-a.json"
        content_path.write_text("{}", encoding="utf-8")

        with self.assertRaisesRegex(
            ValueError, "review content integrity mismatch"
        ):
            REVIEW.prepare(
                self.manifest, self.responses, self.root / "content-review"
            )

    def test_prepare_rejects_tampered_provider_input(self):
        input_path = self.responses / "case-a.input.json"
        input_path.write_text(
            json.dumps(
                {
                    "contractVersion": 1,
                    "promptVersion": "weekly-interpretation-v25",
                    "contentSchemaVersion": 4,
                    "case": "tampered",
                }
            ),
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "input integrity mismatch"):
            REVIEW.prepare(
                self.manifest,
                self.responses,
                self.root / "review",
            )


    def test_load_outputs_rejects_tampered_immutable_resource(self):
        resource = self.root / "resource.txt"
        resource.write_text("stable", encoding="utf-8")
        nested_manifest = self.root / "scripts/eval/manifest.json"
        nested_manifest.parent.mkdir(parents=True)
        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        manifest["promptPath"] = "resource.txt"
        manifest["promptSha256"] = REVIEW.sha256("stable")
        nested_manifest.write_text(json.dumps(manifest), encoding="utf-8")

        REVIEW.load_outputs(nested_manifest, self.responses)

        resource.write_text("tampered", encoding="utf-8")
        with self.assertRaisesRegex(
            ValueError, "immutable resource hash mismatch"
        ):
            REVIEW.load_outputs(nested_manifest, self.responses)


if __name__ == "__main__":
    unittest.main()

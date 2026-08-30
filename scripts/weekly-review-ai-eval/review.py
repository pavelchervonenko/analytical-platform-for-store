#!/usr/bin/env python3
"""Integrity-checked blind review packet for weekly-review AI v25 rendered outputs."""

import argparse
import hashlib
import json
from pathlib import Path


DIMENSIONS = (
    "summaryCoherence",
    "managementUsefulness",
    "nonDuplication",
    "factorMeaning",
    "actionPracticality",
    "clarityAndTone",
)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha256(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_outputs(manifest_path, responses_dir):
    manifest = read_json(manifest_path)
    repository_root = manifest_path.resolve().parents[2]
    for path_key, hash_key in (
        ("promptPath", "promptSha256"),
        ("inputSchemaPath", "inputSchemaSha256"),
        ("selectionSchemaPath", "selectionSchemaSha256"),
        ("contentSchemaPath", "contentSchemaSha256"),
        ("rendererPath", "rendererSha256"),
        ("corpusPath", "corpusSha256"),
        ("fixturesPath", "fixturesSha256"),
        ("shadowRunnerPath", "shadowRunnerSha256"),
        ("reviewScriptPath", "reviewScriptSha256"),
    ):
        if path_key in manifest:
            resource = repository_root / manifest[path_key]
            if not resource.is_file():
                raise ValueError(f"missing immutable resource: {resource}")
            if sha256(resource.read_text(encoding="utf-8")) != manifest[hash_key]:
                raise ValueError(f"immutable resource hash mismatch: {resource}")
    outputs = []
    for case in manifest["cases"]:
        input_path = responses_dir / f"{case['id']}.input.json"
        provider_path = responses_dir / f"{case['id']}.provider.json"
        response_path = responses_dir / f"{case['id']}.json"
        receipt_path = responses_dir / f"{case['id']}.receipt.json"
        if (
            not input_path.is_file()
            or not provider_path.is_file()
            or not response_path.is_file()
            or not receipt_path.is_file()
        ):
            raise ValueError(
                f"missing input/response/receipt for {case['id']}"
            )
        provider_input = input_path.read_text(encoding="utf-8")
        provider_payload = json.loads(provider_input)
        if (
            manifest.get("promptVersion") is not None
            and provider_payload.get("promptVersion")
            != manifest["promptVersion"]
        ):
            raise ValueError(f"prompt version mismatch for {case['id']}")
        if (
            manifest.get("inputSchemaVersion") is not None
            and provider_payload.get("contractVersion")
            != manifest["inputSchemaVersion"]
        ):
            raise ValueError(f"input schema version mismatch for {case['id']}")
        if (
            manifest.get("contentSchemaVersion") is not None
            and provider_payload.get("contentSchemaVersion")
            != manifest["contentSchemaVersion"]
        ):
            raise ValueError(f"content schema version mismatch for {case['id']}")
        provider_response = provider_path.read_text(encoding="utf-8")
        response = response_path.read_text(encoding="utf-8")
        receipt = read_json(receipt_path)
        if receipt.get("corpusVersion") != manifest["version"]:
            raise ValueError(f"corpus version mismatch for {case['id']}")
        if receipt.get("caseId") != case["id"]:
            raise ValueError(f"receipt case mismatch for {case['id']}")
        for manifest_key, receipt_key in (
            ("promptVersion", "promptVersion"),
            ("inputSchemaVersion", "inputSchemaVersion"),
            ("selectionSchemaVersion", "selectionSchemaVersion"),
            ("contentSchemaVersion", "contentSchemaVersion"),
        ):
            if (
                manifest.get(manifest_key) is not None
                and receipt.get(receipt_key) != manifest[manifest_key]
            ):
                raise ValueError(
                    f"receipt {manifest_key} mismatch for {case['id']}"
                )
        if sha256(provider_input) != receipt.get("inputHash"):
            raise ValueError(f"input integrity mismatch for {case['id']}")
        if sha256(provider_response) != receipt.get("providerResponseHash"):
            raise ValueError(
                f"provider response integrity mismatch for {case['id']}"
            )
        if sha256(response) != receipt.get("reviewContentHash"):
            raise ValueError(
                f"review content integrity mismatch for {case['id']}"
            )
        if receipt.get("semanticValidated") is not True:
            raise ValueError(f"semantic gate did not pass for {case['id']}")
        expected_content_kind = manifest.get("reviewContentKind")
        if (
            expected_content_kind is not None
            and receipt.get("reviewContentKind") != expected_content_kind
        ):
            raise ValueError(f"review content kind mismatch for {case['id']}")
        outputs.append((case, provider_input, response, receipt))
    return manifest, outputs


def prepare(manifest_path, responses_dir, review_dir):
    manifest, outputs = load_outputs(manifest_path, responses_dir)
    review_dir.mkdir(parents=True, exist_ok=False)
    ordered = sorted(
        outputs,
        key=lambda value: sha256(manifest["version"] + ":" + value[0]["id"]),
    )
    packet = {"version": manifest["version"], "responses": []}
    assignments = {"version": manifest["version"], "assignments": []}
    scores = {"version": manifest["version"], "completed": False, "scores": []}
    for index, (case, provider_input, response, receipt) in enumerate(
        ordered, start=1
    ):
        token = f"R{index:02d}"
        input_hash = sha256(provider_input)
        response_hash = sha256(response)
        packet["responses"].append(
            {
                "token": token,
                "input": json.loads(provider_input),
                "content": json.loads(response),
            }
        )
        assignments["assignments"].append(
            {
                "token": token,
                "caseId": case["id"],
                "inputSha256": input_hash,
                "responseSha256": response_hash,
                "requestHash": receipt["requestHash"],
                "required": case["required"],
                "forbidden": case["forbidden"],
            }
        )
        scores["scores"].append(
            {
                "token": token,
                "dimensions": {dimension: None for dimension in DIMENSIONS},
                "requiredFindingsCovered": None,
                "forbiddenFindings": [],
                "criticalErrors": [],
                "comment": "",
            }
        )
    write_json(review_dir / "packet.json", packet)
    write_json(review_dir / "assignments.DO_NOT_OPEN.json", assignments)
    write_json(review_dir / "scores.json", scores)


def finalize(manifest_path, responses_dir, review_dir, report_path):
    manifest, outputs = load_outputs(manifest_path, responses_dir)
    assignments = read_json(review_dir / "assignments.DO_NOT_OPEN.json")
    scores = read_json(review_dir / "scores.json")
    if scores.get("completed") is not True:
        raise ValueError("blind scores are not marked completed")
    assignment_by_token = {
        value["token"]: value for value in assignments["assignments"]
    }
    score_by_token = {value["token"]: value for value in scores["scores"]}
    if set(assignment_by_token) != set(score_by_token):
        raise ValueError("blind score token set is incomplete")
    output_by_case = {
        case["id"]: (provider_input, response)
        for case, provider_input, response, _ in outputs
    }
    cases = []
    passed = True
    for token, assignment in assignment_by_token.items():
        score = score_by_token[token]
        provider_input, response = output_by_case[assignment["caseId"]]
        if sha256(provider_input) != assignment["inputSha256"]:
            raise ValueError(f"input integrity mismatch for {token}")
        if sha256(response) != assignment["responseSha256"]:
            raise ValueError(f"response integrity mismatch for {token}")
        dimensions = score.get("dimensions", {})
        values = [dimensions.get(dimension) for dimension in DIMENSIONS]
        if any(
            not isinstance(value, int) or value < 1 or value > 5
            for value in values
        ):
            raise ValueError(f"invalid dimension score for {token}")
        average = sum(values) / len(values)
        case_passed = (
            average >= manifest["minimumAverage"]
            and min(values) >= manifest.get("minimumDimension", 1)
            and score.get("requiredFindingsCovered") is True
            and not score.get("forbiddenFindings")
            and not score.get("criticalErrors")
        )
        passed = passed and case_passed
        cases.append(
            {
                "caseId": assignment["caseId"],
                "average": round(average, 4),
                "passed": case_passed,
                "dimensions": dimensions,
                "comment": score.get("comment", ""),
            }
        )
    report = {
        "version": manifest["version"],
        "decision": "CANDIDATE_ELIGIBLE_FOR_CANARY" if passed else "REJECTED",
        "allCasesPassed": passed,
        "cases": sorted(cases, key=lambda value: value["caseId"]),
    }
    write_json(report_path, report)
    return 0 if passed else 1


def status(manifest_path, responses_dir):
    manifest, outputs = load_outputs(manifest_path, responses_dir)
    print(
        f"{manifest['version']}: "
        f"{len(outputs)}/{len(manifest['cases'])} semantic outputs ready"
    )
    return 0


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("status", "prepare", "finalize"))
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path("scripts/weekly-review-ai-eval/manifest-v6.json"),
    )
    parser.add_argument("--responses-dir", type=Path, required=True)
    parser.add_argument("--review-dir", type=Path)
    parser.add_argument("--report", type=Path)
    return parser.parse_args()


def main():
    arguments = parse_args()
    if arguments.command == "status":
        return status(arguments.manifest, arguments.responses_dir)
    if arguments.review_dir is None:
        raise ValueError("--review-dir is required")
    if arguments.command == "prepare":
        prepare(arguments.manifest, arguments.responses_dir, arguments.review_dir)
        return 0
    if arguments.report is None:
        raise ValueError("--report is required")
    return finalize(
        arguments.manifest,
        arguments.responses_dir,
        arguments.review_dir,
        arguments.report,
    )


if __name__ == "__main__":
    raise SystemExit(main())

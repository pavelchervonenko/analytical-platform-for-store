#!/usr/bin/env python3
"""Deterministic offline gate for versioned weekly interpretation fixtures."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from jsonschema import Draft202012Validator


def load_json(path: Path) -> object:
    with path.open("r", encoding="utf-8") as source:
        return json.load(source)


def strings(node: object, path: str = "$"):
    if isinstance(node, dict):
        for key, value in node.items():
            yield from strings(value, f"{path}.{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from strings(value, f"{path}[{index}]")
    elif isinstance(node, str):
        yield path, node


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


def evidence_refs(output: object) -> set[str]:
    result: set[str] = set()
    for value in values_for_key(output, "evidenceRefs"):
        if isinstance(value, list):
            result.update(item for item in value if isinstance(item, str))
    return result


def validate_case(root: Path, validator: Draft202012Validator, case: dict) -> list[str]:
    case_id = case.get("id", "unnamed")
    output_path = root / case["output"]
    output = load_json(output_path)
    failures = [
        f"{case_id}: schema {error.json_path}: {error.message}"
        for error in sorted(validator.iter_errors(output), key=lambda item: item.json_path)
    ]

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
    forbidden = [re.compile(pattern, re.IGNORECASE) for pattern in assertions.get("forbiddenPatterns", [])]
    for field_path, value in strings(output):
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--manifest",
        default="scripts/llm-eval/manifest.example.json",
        help="Path to the versioned evaluation manifest",
    )
    arguments = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    manifest_path = (repository / arguments.manifest).resolve()
    manifest = load_json(manifest_path)
    schema_path = repository / manifest["schema"]
    validator = Draft202012Validator(load_json(schema_path))
    failures: list[str] = []
    cases = manifest.get("cases", [])
    if not cases:
        failures.append("evaluation manifest must contain at least one case")
    for case in cases:
        failures.extend(validate_case(repository, validator, case))

    if failures:
        print(f"LLM evaluation failed: {len(failures)} violation(s).", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print(f"LLM evaluation passed: {len(cases)} case(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

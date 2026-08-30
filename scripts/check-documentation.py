#!/usr/bin/env python3
"""Offline integrity checks for repository documentation."""

from __future__ import annotations

import argparse
import csv
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from io import StringIO
from pathlib import Path
from urllib.parse import unquote, urlparse


PROJECT_ROOT = Path(__file__).resolve().parents[1]
INVENTORY = PROJECT_ROOT / "docs/maintenance/documentation-inventory.tsv"
WARNING_BASELINE = PROJECT_ROOT / "docs/maintenance/documentation-warning-baseline.txt"
INVENTORY_FIELDS = (
    "path",
    "tracking",
    "kind",
    "owner",
    "migration_status",
    "action",
    "target",
    "verification",
)
DOCUMENT_SUFFIXES = {".md", ".adoc", ".rst"}
BACKUP_SUFFIXES = {".orig", ".bak", ".rej"}
ALLOWED_ACTIONS = {
    "archive",
    "consolidate",
    "delete-candidate",
    "history",
    "keep",
    "removed",
    "rewrite",
    "runtime-keep",
    "split",
}
ALLOWED_OPERATIONS = {
    "read-only", "reversible-write", "migration", "recovery", "destructive"
}
ALLOWED_ENVIRONMENTS = {"local", "test", "staging", "production"}
ALLOWED_RISKS = {"low", "medium", "high", "critical"}
RISK_RANK = {"low": 0, "medium": 1, "high": 2, "critical": 3}
ALLOWED_VERIFICATION_LEVELS = {
    "static", "local", "staging", "production-read-only", "production-drill"
}
TARGET_PREFIXES = (
    "docs/current/",
    "docs/runbooks/",
    "docs/security/",
    "docs/decisions/",
    "docs/history/",
    "docs/archive/",
)
NORMATIVE_MAINTENANCE = {
    "docs/maintenance/documentation-inventory.md",
    "docs/maintenance/documentation-ownership.md",
    "docs/maintenance/documentation-policy.md",
    "docs/maintenance/documentation-reform-plan.md",
}
ALLOWED_STATUS = {
    "current": {"draft", "current", "superseded"},
    "runbook": {"draft", "current", "superseded"},
    "decision": {"proposed", "accepted", "rejected", "superseded"},
    "evidence": {"historical"},
    "archive": {"archived"},
    "working": {"draft", "closed"},
}
LINK_PATTERN = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
REFERENCE_DEFINITION_PATTERN = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*(\S+)", re.MULTILINE)
FENCE_PATTERN = re.compile(r"```.*?```|~~~.*?~~~", re.DOTALL)
ISO_DATE_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}")


@dataclass
class Result:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def error(self, message: str) -> None:
        self.errors.append(message)

    def warning(self, message: str) -> None:
        self.warnings.append(message)


def git_lines(*arguments: str) -> set[str]:
    completed = subprocess.run(
        ["git", *arguments],
        cwd=PROJECT_ROOT,
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    )
    return set(completed.stdout.splitlines())


def parse_inventory(source_text: str, label: str, result: Result) -> list[dict[str, str]]:
    with StringIO(source_text, newline="") as source:
        reader = csv.DictReader(source, delimiter="\t")
        if tuple(reader.fieldnames or ()) != INVENTORY_FIELDS:
            result.error(f"{label}: inventory header must be {INVENTORY_FIELDS!r}")
            return []
        rows = list(reader)
    seen: set[str] = set()
    for line_number, row in enumerate(rows, start=2):
        empty = [name for name in INVENTORY_FIELDS if not row[name].strip()]
        if empty:
            result.error(f"{label}:{line_number}: empty fields: {', '.join(empty)}")
        path = row["path"]
        if path in seen:
            result.error(f"{label}:{line_number}: duplicate path: {path}")
        seen.add(path)
    return rows


def load_inventory(result: Result) -> list[dict[str, str]]:
    if not INVENTORY.is_file():
        result.error("inventory is missing")
        return []
    return parse_inventory(INVENTORY.read_text(encoding="utf-8"), "inventory", result)


def load_base_inventory(base_ref: str, result: Result) -> list[dict[str, str]]:
    completed = subprocess.run(
        ["git", "show", f"{base_ref}:docs/maintenance/documentation-inventory.tsv"],
        cwd=PROJECT_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if completed.returncode != 0:
        result.error(f"cannot read base inventory from {base_ref!r}")
        return []
    return parse_inventory(completed.stdout, f"inventory@{base_ref}", result)


def discovered_documentation(
    rows: list[dict[str, str]], tracked: set[str]
) -> set[str]:
    discovered = {
        path.relative_to(PROJECT_ROOT).as_posix()
        for path in (PROJECT_ROOT / "docs").rglob("*")
        if path.is_file()
    }
    discovered.update(
        path
        for path in tracked
        if not path.startswith("docs/") and Path(path).suffix.lower() in DOCUMENT_SUFFIXES
    )
    discovered.update(row["path"] for row in rows if not row["path"].startswith("docs/"))
    return discovered


def check_inventory(
    rows: list[dict[str, str]], base_rows: list[dict[str, str]], result: Result
) -> None:
    tracked = git_lines("ls-files")
    ignored = git_lines("ls-files", "--others", "--ignored", "--exclude-standard")
    registered = {row["path"] for row in rows}
    current_by_path = {row["path"]: row for row in rows}
    discovered = discovered_documentation(rows, tracked)
    for path in sorted(discovered - registered):
        result.error(f"unregistered documentation: {path}")
    for row in rows:
        path = row["path"]
        absolute = PROJECT_ROOT / path
        if row["action"] not in ALLOWED_ACTIONS:
            result.error(f"invalid action for {path}: {row['action']}")
        if row["tracking"] == "tracked":
            if path not in tracked or not absolute.is_file():
                result.error(f"inventory expects tracked file: {path}")
        elif row["tracking"] == "ignored":
            if absolute.exists() and path not in ignored:
                result.error(f"inventory expects ignored file: {path}")
        elif row["tracking"] == "removed":
            if absolute.exists() or path in tracked or path in ignored:
                result.error(f"inventory tombstone still exists: {path}")
            if row["action"] != "removed":
                result.error(f"inventory tombstone must use action=removed: {path}")
            check_tombstone_evidence(row, tracked, result)
        else:
            result.error(f"invalid tracking value for {path}: {row['tracking']}")
        if path.startswith(("docs/prompts/", "docs/schemas/")):
            if row["action"] != "runtime-keep":
                result.error(f"runtime artifact is not protected: {path}")
        if row["action"] == "delete-candidate":
            verification = row["verification"].lower()
            fragment_gate = (
                "backup-fragment-map" in verification
                or "record unique fragments" in verification
            )
            if not fragment_gate or "reviewer sign-off" not in verification:
                result.error(f"unsafe delete candidate gate: {path}")
    check_base_inventory(current_by_path, base_rows, result)


def check_base_inventory(
    current_by_path: dict[str, dict[str, str]],
    base_rows: list[dict[str, str]],
    result: Result,
) -> None:
    base_by_path = {row["path"]: row for row in base_rows}
    for base_row in base_rows:
        path = base_row["path"]
        current = current_by_path.get(path)
        if current is None:
            result.error(f"inventory row removed without tombstone: {path}")
            continue
        if base_row["action"] == "runtime-keep":
            if current["action"] != "runtime-keep" or current["tracking"] == "removed":
                result.error(f"runtime artifact protection was weakened: {path}")
    for path, current in current_by_path.items():
        if current["tracking"] != "removed":
            continue
        base = base_by_path.get(path)
        if base is None:
            result.error(f"inventory tombstone has no tracked predecessor: {path}")
        elif base["action"] != "delete-candidate":
            result.error(f"inventory removal skipped delete-candidate stage: {path}")


def check_tombstone_evidence(
    row: dict[str, str], tracked: set[str], result: Result
) -> None:
    path = row["path"]
    references = dict(
        re.findall(
            r"(?:^|;)\s*(fragment-map|reviewer-sign-off)=([^;]+)",
            row["verification"],
        )
    )
    for key in ("fragment-map", "reviewer-sign-off"):
        raw_reference = references.get(key, "").strip()
        if not raw_reference:
            result.error(f"inventory tombstone lacks {key} evidence: {path}")
            continue
        candidate = (PROJECT_ROOT / raw_reference).resolve()
        try:
            relative = candidate.relative_to(PROJECT_ROOT.resolve()).as_posix()
        except ValueError:
            result.error(f"inventory tombstone {key} escapes repository: {path}")
            continue
        if relative not in tracked or not candidate.is_file():
            result.error(f"inventory tombstone {key} is not a tracked file: {path}")
            continue
        content = candidate.read_text(encoding="utf-8")
        if path not in content:
            result.error(f"inventory tombstone {key} does not name removed path: {path}")
            continue
        if key == "reviewer-sign-off":
            try:
                metadata = parse_frontmatter(content)
            except ValueError:
                metadata = None
            if (
                metadata is None
                or metadata.get("doc_type") != "evidence"
                or metadata.get("status") != "historical"
                or metadata.get("verdict") not in {"PASS", "PASS_WITH_LIMITS"}
                or not metadata.get("required_reviewers")
            ):
                result.error(f"inventory tombstone reviewer sign-off is not PASS evidence: {path}")


def parse_scalar(value: str) -> object:
    value = value.strip()
    if value in {"null", "~"}:
        return None
    if value == "[]":
        return []
    if value in {"true", "false"}:
        return value == "true"
    if (value.startswith('"') and value.endswith('"')) or (
        value.startswith("'") and value.endswith("'")
    ):
        return value[1:-1]
    return value


def parse_frontmatter(text: str) -> dict[str, object] | None:
    if not text.startswith("---\n"):
        return None
    end = text.find("\n---\n", 4)
    if end < 0:
        return None
    lines = text[4:end].splitlines()
    values: dict[str, object] = {}
    active_list: str | None = None
    active_mapping: dict[str, object] | None = None
    for raw_line in lines:
        if not raw_line.strip() or raw_line.lstrip().startswith("#"):
            continue
        if not raw_line.startswith((" ", "\t")):
            key, separator, raw_value = raw_line.partition(":")
            if not separator or not re.fullmatch(r"[a-z][a-z0-9_]*", key):
                raise ValueError(f"invalid top-level metadata line: {raw_line!r}")
            value = parse_scalar(raw_value)
            if raw_value.strip() == "":
                value = []
                active_list = key
                active_mapping = None
            else:
                active_list = None
                active_mapping = None
            if key in values:
                raise ValueError(f"duplicate metadata key: {key}")
            values[key] = value
        elif active_list and re.match(r"^\s+-\s+", raw_line):
            item = re.sub(r"^\s+-\s+", "", raw_line)
            current = values[active_list]
            if isinstance(current, list):
                mapping_match = re.fullmatch(
                    r"([a-z][a-z0-9_]*):(?:\s+|$)(.*)", item
                )
                if mapping_match:
                    active_mapping = {
                        mapping_match.group(1): parse_scalar(mapping_match.group(2))
                    }
                    current.append(active_mapping)
                else:
                    active_mapping = None
                    current.append(parse_scalar(item))
        elif active_mapping is not None and re.match(
            r"^\s{4,}[a-z][a-z0-9_]*:\s*", raw_line
        ):
            nested_key, _, nested_value = raw_line.strip().partition(":")
            if nested_key in active_mapping:
                raise ValueError(f"duplicate nested metadata key: {nested_key}")
            active_mapping[nested_key] = parse_scalar(nested_value)
        elif raw_line.startswith(("  ", "\t")):
            continue
        else:
            raise ValueError(f"unsupported metadata line: {raw_line!r}")
    return values


def requires_metadata(relative: str) -> bool:
    return relative in NORMATIVE_MAINTENANCE or relative.startswith(TARGET_PREFIXES)


def require_keys(
    relative: str,
    metadata: dict[str, object],
    keys: tuple[str, ...],
    result: Result,
) -> None:
    for key in keys:
        if key not in metadata:
            result.error(f"{relative}: required metadata is missing: {key}")


def require_nonempty(
    relative: str,
    metadata: dict[str, object],
    keys: tuple[str, ...],
    result: Result,
) -> None:
    for key in keys:
        value = metadata.get(key)
        if value is None or value == "" or value == []:
            result.error(f"{relative}: metadata must be non-empty: {key}")


def string_list(
    relative: str,
    metadata: dict[str, object],
    key: str,
    allowed: set[str] | None,
    result: Result,
) -> set[str]:
    value = metadata.get(key)
    if not isinstance(value, list) or any(
        not isinstance(item, str) or not item for item in value
    ):
        result.error(f"{relative}: metadata must be a string list: {key}")
        return set()
    items = set(value)
    if allowed is not None and not items <= allowed:
        result.error(f"{relative}: invalid values in {key}: {sorted(items - allowed)}")
    return items


def require_iso_date(
    relative: str, metadata: dict[str, object], key: str, result: Result
) -> None:
    value = metadata.get(key)
    if not isinstance(value, str) or not ISO_DATE_PATTERN.fullmatch(value):
        result.error(f"{relative}: metadata must be an ISO date: {key}")


def check_runbook_metadata(
    relative: str, metadata: dict[str, object], status: str, result: Result
) -> None:
    operation = metadata.get("operation_type")
    environments = string_list(
        relative, metadata, "environments", ALLOWED_ENVIRONMENTS, result
    )
    achieved = string_list(
        relative, metadata, "verification_levels", ALLOWED_VERIFICATION_LEVELS, result
    )
    required = string_list(
        relative,
        metadata,
        "required_verification_levels",
        ALLOWED_VERIFICATION_LEVELS,
        result,
    )
    risk = metadata.get("risk_level")
    if operation not in ALLOWED_OPERATIONS:
        result.error(f"{relative}: invalid operation_type: {operation}")
    if risk not in ALLOWED_RISKS:
        result.error(f"{relative}: invalid risk_level: {risk}")
    evidence = metadata.get("verification_evidence")
    evidence_levels: set[str] = set()
    if not isinstance(evidence, list) or not evidence:
        result.error(f"{relative}: verification_evidence must be a non-empty list")
    else:
        for index, item in enumerate(evidence, start=1):
            if not isinstance(item, dict):
                result.error(f"{relative}: verification_evidence #{index} must be a mapping")
                continue
            missing = {"level", "scope", "verified_at", "evidence"} - set(item)
            if missing:
                result.error(
                    f"{relative}: verification_evidence #{index} lacks {sorted(missing)}"
                )
                continue
            level = item["level"]
            if level not in ALLOWED_VERIFICATION_LEVELS:
                result.error(f"{relative}: invalid evidence level: {level}")
            else:
                evidence_levels.add(str(level))
            for key in ("scope", "evidence"):
                if not isinstance(item[key], str) or not item[key].strip():
                    result.error(f"{relative}: empty evidence {key} in record #{index}")
            verified_at = item["verified_at"]
            if not isinstance(verified_at, str) or not ISO_DATE_PATTERN.fullmatch(verified_at):
                result.error(f"{relative}: invalid evidence date in record #{index}")
    if status != "current":
        return
    require_iso_date(relative, metadata, "last_verified", result)
    require_iso_date(relative, metadata, "last_rehearsed", result)
    string_list(relative, metadata, "review_triggers", None, result)
    string_list(relative, metadata, "source_of_truth", None, result)
    if not required <= achieved:
        result.error(f"{relative}: required verification levels were not achieved")
    if not achieved <= evidence_levels:
        result.error(f"{relative}: achieved levels lack structured evidence")
    if (
        "production" not in environments
        or operation not in ALLOWED_OPERATIONS
        or risk not in ALLOWED_RISKS
    ):
        return
    minimum_risk = {
        "read-only": "low",
        "reversible-write": "medium",
        "migration": "high",
        "recovery": "high",
        "destructive": "critical",
    }[str(operation)]
    if RISK_RANK[str(risk)] < RISK_RANK[minimum_risk]:
        result.error(f"{relative}: production {operation} requires risk >= {minimum_risk}")
    mandatory = {"production-read-only"}
    if operation != "read-only":
        mandatory.add("staging")
    if not mandatory <= required:
        result.error(
            f"{relative}: production {operation} requires gates {sorted(mandatory)}"
        )
    reviewers = string_list(relative, metadata, "required_reviewers", None, result)
    if RISK_RANK[str(risk)] >= RISK_RANK["high"] and "operations" not in reviewers:
        result.error(f"{relative}: high-risk production runbook requires operations review")


def check_metadata(result: Result) -> None:
    for path in sorted((PROJECT_ROOT / "docs").rglob("*.md")):
        relative = path.relative_to(PROJECT_ROOT).as_posix()
        if not requires_metadata(relative):
            continue
        try:
            metadata = parse_frontmatter(path.read_text(encoding="utf-8"))
        except ValueError as exception:
            result.error(f"{relative}: {exception}")
            continue
        if metadata is None:
            result.error(f"{relative}: YAML front matter is required")
            continue
        require_keys(
            relative,
            metadata,
            ("doc_schema", "doc_type", "status", "owner", "audience", "required_reviewers"),
            result,
        )
        require_nonempty(
            relative,
            metadata,
            ("doc_schema", "doc_type", "status", "owner", "audience"),
            result,
        )
        if str(metadata.get("doc_schema")) != "1":
            result.error(f"{relative}: unsupported doc_schema")
        string_list(relative, metadata, "audience", None, result)
        string_list(relative, metadata, "required_reviewers", None, result)
        document_type = str(metadata.get("doc_type", ""))
        status = str(metadata.get("status", ""))
        if status not in ALLOWED_STATUS.get(document_type, set()):
            result.error(f"{relative}: invalid lifecycle {document_type}/{status}")
        if document_type == "current":
            require_keys(relative, metadata, ("last_verified", "review_triggers"), result)
            if status == "current":
                require_iso_date(relative, metadata, "last_verified", result)
                string_list(relative, metadata, "review_triggers", None, result)
                require_nonempty(
                    relative,
                    metadata,
                    ("implementation_sources", "verification_sources"),
                    result,
                )
            if relative == "docs/current/project-state.md" and status == "current":
                require_nonempty(relative, metadata, ("runtime_evidence",), result)
        elif document_type == "runbook":
            require_keys(
                relative,
                metadata,
                (
                    "last_verified",
                    "last_rehearsed",
                    "operation_type",
                    "environments",
                    "risk_level",
                    "verification_levels",
                    "required_verification_levels",
                    "verification_evidence",
                    "review_triggers",
                ),
                result,
            )
            check_runbook_metadata(relative, metadata, status, result)
        elif document_type == "decision":
            require_keys(relative, metadata, ("decision_date", "implementation_status"), result)
        elif document_type == "evidence":
            require_keys(
                relative,
                metadata,
                ("snapshot_date", "verdict", "verdict_scope", "source_of_truth"),
                result,
            )
            require_nonempty(
                relative,
                metadata,
                ("snapshot_date", "verdict", "verdict_scope", "source_of_truth"),
                result,
            )
            require_iso_date(relative, metadata, "snapshot_date", result)
        elif document_type == "archive":
            require_keys(relative, metadata, ("archived_at", "superseded_by"), result)
        elif document_type == "working":
            require_keys(relative, metadata, ("review_by", "source_material", "exit_target"), result)


def markdown_links(text: str) -> list[str]:
    without_fences = FENCE_PATTERN.sub("", text)
    targets: list[str] = []
    for match in LINK_PATTERN.finditer(without_fences):
        raw = match.group(1).strip()
        if raw.startswith("<") and ">" in raw:
            raw = raw[1 : raw.index(">")]
        else:
            raw = raw.split(maxsplit=1)[0]
        targets.append(raw)
    targets.extend(REFERENCE_DEFINITION_PATTERN.findall(without_fences))
    return targets


def local_link_target(source: Path, raw_target: str) -> Path | None:
    if not raw_target or raw_target.startswith("<"):
        return None
    if raw_target.startswith("#"):
        return source.resolve()
    parsed = urlparse(raw_target)
    if parsed.scheme or parsed.netloc:
        return None
    path = unquote(parsed.path)
    if not path:
        return None
    if path.startswith("/"):
        return (PROJECT_ROOT / path.lstrip("/")).resolve()
    return (source.parent / path).resolve()


def markdown_anchors(text: str) -> set[str]:
    anchors: set[str] = set()
    counts: dict[str, int] = {}
    for raw_line in FENCE_PATTERN.sub("", text).splitlines():
        match = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", raw_line)
        if not match:
            continue
        heading = re.sub(r"!?\[([^\]]+)\]\([^)]+\)", r"\1", match.group(1))
        heading = re.sub(r"[`*_~]", "", heading).strip().lower()
        slug = re.sub(r"[^\w\- ]", "", heading, flags=re.UNICODE)
        slug = re.sub(r"\s+", "-", slug)
        duplicate = counts.get(slug, 0)
        counts[slug] = duplicate + 1
        anchors.add(slug if duplicate == 0 else f"{slug}-{duplicate}")
    return anchors


def check_links(strict: bool, result: Result) -> None:
    for relative in sorted(git_lines("ls-files", "*.md")):
        source = PROJECT_ROOT / relative
        if not source.is_file():
            continue
        for raw_target in markdown_links(source.read_text(encoding="utf-8")):
            target = local_link_target(source, raw_target)
            if target is None:
                continue
            try:
                target.relative_to(PROJECT_ROOT.resolve())
            except ValueError:
                result.error(f"{relative}: local link escapes repository: {raw_target}")
                continue
            message: str | None = None
            if not target.exists():
                message = f"{relative}: broken local link: {raw_target}"
            else:
                fragment = unquote(urlparse(raw_target).fragment)
                if fragment and target.suffix.lower() == ".md":
                    anchors = markdown_anchors(target.read_text(encoding="utf-8"))
                    if fragment not in anchors:
                        message = f"{relative}: broken local anchor: {raw_target}"
            if message is not None:
                if strict or requires_metadata(relative):
                    result.error(message)
                else:
                    result.warning(message)


def check_backup_files(strict: bool, result: Result) -> None:
    for path in sorted((PROJECT_ROOT / "docs").rglob("*")):
        if path.is_file() and path.suffix.lower() in BACKUP_SUFFIXES:
            message = f"backup artifact remains under docs: {path.relative_to(PROJECT_ROOT)}"
            if strict:
                result.error(message)
            else:
                result.warning(message)


def check_orphans(strict: bool, result: Result) -> None:
    current_root = PROJECT_ROOT / "docs/current"
    if not current_root.exists():
        return
    indexes = [PROJECT_ROOT / "README.md", PROJECT_ROOT / "docs/README.md", current_root / "README.md"]
    linked: set[Path] = set()
    for index in indexes:
        if not index.is_file():
            continue
        for raw_target in markdown_links(index.read_text(encoding="utf-8")):
            target = local_link_target(index, raw_target)
            if target is not None:
                linked.add(target)
    for path in sorted(current_root.rglob("*.md")):
        if path.name == "README.md" or path.resolve() in linked:
            continue
        message = f"current document is not linked from an entrypoint: {path.relative_to(PROJECT_ROOT)}"
        if strict:
            result.error(message)
        else:
            result.warning(message)


def enforce_warning_baseline(strict: bool, result: Result) -> None:
    if strict:
        return
    if not WARNING_BASELINE.is_file():
        result.error("documentation warning baseline is missing")
        return
    expected = {
        line.strip()
        for line in WARNING_BASELINE.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }
    actual = set(result.warnings)
    for message in sorted(actual - expected):
        result.error(f"new documentation warning is not baselined: {message}")


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Treat legacy broken links, backup files and orphan current docs as errors.",
    )
    parser.add_argument(
        "--base-ref",
        default=os.environ.get("DOCUMENTATION_BASE_REF", "HEAD"),
        help="Git revision whose inventory rows must remain protected by tombstones.",
    )
    return parser.parse_args()


def main() -> None:
    arguments = parse_arguments()
    result = Result()
    rows = load_inventory(result)
    base_rows = load_base_inventory(arguments.base_ref, result)
    if rows:
        check_inventory(rows, base_rows, result)
    check_metadata(result)
    check_links(arguments.strict, result)
    check_backup_files(arguments.strict, result)
    check_orphans(arguments.strict, result)
    enforce_warning_baseline(arguments.strict, result)
    for warning in result.warnings:
        print(f"DOCUMENTATION WARNING: {warning}", file=sys.stderr)
    for error in result.errors:
        print(f"DOCUMENTATION CHECK FAILED: {error}", file=sys.stderr)
    if result.errors:
        raise SystemExit(1)
    print(
        "Documentation integrity passed: "
        f"{len(rows)} inventory rows, {len(result.warnings)} baseline warnings."
    )


if __name__ == "__main__":
    main()

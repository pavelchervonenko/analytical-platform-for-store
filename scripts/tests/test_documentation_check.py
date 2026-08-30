#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-documentation.py"
SPEC = importlib.util.spec_from_file_location("check_documentation", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("documentation checker cannot be loaded")
CHECK = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECK
SPEC.loader.exec_module(CHECK)


class DocumentationCheckTest(unittest.TestCase):

    def test_parses_supported_frontmatter(self) -> None:
        metadata = CHECK.parse_frontmatter(
            """---
doc_schema: 1
doc_type: current
status: current
runtime_evidence: []
audience:
  - developer
---
# Title
"""
        )
        self.assertEqual(metadata["doc_type"], "current")
        self.assertEqual(metadata["runtime_evidence"], [])
        self.assertEqual(metadata["audience"], ["developer"])

    def test_parses_structured_verification_evidence(self) -> None:
        metadata = CHECK.parse_frontmatter(
            """---
verification_evidence:
  - level: staging
    scope: exact image
    verified_at: 2026-08-31
    evidence: docs/history/example.md
---
"""
        )
        self.assertEqual(metadata["verification_evidence"][0]["level"], "staging")
        self.assertEqual(metadata["verification_evidence"][0]["verified_at"], "2026-08-31")

    def test_keeps_colon_source_identifier_as_string(self) -> None:
        metadata = CHECK.parse_frontmatter(
            """---
source_of_truth:
  - production-runtime:sanitized-check
---
"""
        )
        self.assertEqual(
            metadata["source_of_truth"], ["production-runtime:sanitized-check"]
        )

    def test_rejects_duplicate_frontmatter_keys(self) -> None:
        with self.assertRaisesRegex(ValueError, "duplicate metadata key"):
            CHECK.parse_frontmatter(
                """---
status: draft
status: current
---
"""
            )

    def test_ignores_links_inside_fenced_code(self) -> None:
        links = CHECK.markdown_links(
            """[real](docs/current.md)
```markdown
[example](missing.md)
```
"""
        )
        self.assertEqual(links, ["docs/current.md"])

    def test_resolves_relative_link_without_anchor(self) -> None:
        source = Path("/repo/docs/current/example.md")
        target = CHECK.local_link_target(source, "../README.md#start")
        self.assertEqual(target, Path("/repo/docs/README.md"))

    def test_resolves_repository_root_link(self) -> None:
        source = CHECK.PROJECT_ROOT / "docs/current/example.md"
        target = CHECK.local_link_target(source, "/docs/README.md")
        self.assertEqual(target, CHECK.PROJECT_ROOT / "docs/README.md")

    def test_detects_base_inventory_row_removal(self) -> None:
        result = CHECK.Result()
        base = [{"path": "docs/prompts/example.md", "action": "runtime-keep"}]
        CHECK.check_base_inventory({}, base, result)
        self.assertIn("inventory row removed without tombstone", result.errors[0])

    def test_detects_weakened_runtime_artifact(self) -> None:
        result = CHECK.Result()
        base = [{"path": "docs/prompts/example.md", "action": "runtime-keep"}]
        current = {
            "docs/prompts/example.md": {
                "path": "docs/prompts/example.md",
                "action": "removed",
                "tracking": "removed",
            }
        }
        CHECK.check_base_inventory(current, base, result)
        self.assertIn("runtime artifact protection was weakened", result.errors[0])

    def test_rejects_tombstone_with_fake_evidence(self) -> None:
        result = CHECK.Result()
        row = {
            "path": "docs/obsolete.md",
            "verification": "fragment-map=N/A;reviewer-sign-off=N/A",
        }
        CHECK.check_tombstone_evidence(row, set(), result)
        self.assertEqual(len(result.errors), 2)
        self.assertTrue(all("not a tracked file" in error for error in result.errors))

    def test_rejects_unsafe_current_production_runbook(self) -> None:
        metadata = CHECK.parse_frontmatter(
            """---
operation_type: destructive
environments:
  - production
risk_level: low
last_verified: null
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - static
source_of_truth:
  - deploy/example.sh
verification_evidence:
  - level: static
    scope: source review
    verified_at: 2026-08-31
    evidence: docs/history/review.md
review_triggers:
  - deployment-change
required_reviewers:
  - operations
---
"""
        )
        result = CHECK.Result()
        CHECK.check_runbook_metadata("docs/runbooks/example.md", metadata, "current", result)
        joined = "\n".join(result.errors)
        self.assertIn("ISO date: last_rehearsed", joined)
        self.assertIn("requires risk >= critical", joined)
        self.assertIn("requires gates", joined)

    def test_extracts_reference_style_target(self) -> None:
        links = CHECK.markdown_links("See [guide][docs].\n\n[docs]: /docs/README.md\n")
        self.assertEqual(links, ["/docs/README.md"])

    def test_resolves_same_file_anchor_to_source(self) -> None:
        source = CHECK.PROJECT_ROOT / "docs/current/example.md"
        self.assertEqual(CHECK.local_link_target(source, "#missing"), source)

    def test_nonempty_metadata_rejects_empty_list(self) -> None:
        result = CHECK.Result()
        CHECK.require_nonempty(
            "docs/current/example.md",
            {"runtime_evidence": []},
            ("runtime_evidence",),
            result,
        )
        self.assertEqual(len(result.errors), 1)


if __name__ == "__main__":
    unittest.main()

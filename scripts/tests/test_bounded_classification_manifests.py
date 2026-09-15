import json
import re
import unittest
from collections import defaultdict
from datetime import date
from decimal import Decimal
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_DIR = PROJECT_ROOT / "scripts" / "reconciliation" / "manifests"


class BoundedClassificationManifestTest(unittest.TestCase):
    def manifests(self):
        paths = sorted(MANIFEST_DIR.glob("2026-??-magazin.json"))
        self.assertEqual(
            [path.name for path in paths],
            [
                "2026-01-magazin.json",
                "2026-02-magazin.json",
                "2026-03-magazin.json",
            ],
        )
        return [(path, json.loads(path.read_text(encoding="utf-8"))) for path in paths]

    def test_manifests_are_bounded_and_internally_consistent(self):
        for path, manifest in self.manifests():
            with self.subTest(path=path.name):
                operation_id = manifest["operation_id"]
                self.assertEqual(manifest["manifest_version"], 1)
                self.assertRegex(
                    operation_id,
                    r"^classification-correction-2026-0[1-3]-magazin-v1$",
                )
                self.assertEqual(manifest["required_schema_version"], "51")
                self.assertEqual(manifest["connection_key"], "livesklad-default")
                self.assertEqual(manifest["store_name"], "МАГАЗИН")
                self.assertEqual(manifest["store_timezone"], "Europe/Kaliningrad")
                self.assertTrue((PROJECT_ROOT / manifest["evidence_path"]).is_file())

                start = date.fromisoformat(manifest["period_start"])
                end = date.fromisoformat(manifest["period_end"])
                self.assertEqual(start.day, 1)
                self.assertEqual((end.year * 12 + end.month) - (start.year * 12 + start.month), 1)

                items = manifest["items"]
                expected = manifest["expected"]
                self.assertEqual(len(items), expected["item_count"])
                self.assertEqual(
                    sum((Decimal(item["quantity"]) for item in items), Decimal()),
                    Decimal(expected["quantity"]),
                )
                self.assertEqual(
                    sum((Decimal(item["net_amount"]) for item in items), Decimal()),
                    Decimal(expected["net_amount"]),
                )
                self.assertEqual(
                    sum((Decimal(item["cost_amount"]) for item in items), Decimal()),
                    Decimal(expected["cost_amount"]),
                )
                self.assertEqual(
                    sum(
                        item["expected_analytics_category"]
                        != item["target_analytics_category"]
                        for item in items
                    ),
                    expected["analytics_changed_item_count"],
                )
                self.assertEqual(
                    sum(
                        item["expected_payroll_category"]
                        != item["target_payroll_category"]
                        for item in items
                    ),
                    expected["payroll_changed_item_count"],
                )

                identities = set()
                analytics_by_product = defaultdict(set)
                payroll_by_product = defaultdict(set)
                for item in items:
                    identity = (item["document_external_id"], item["item_external_id"])
                    self.assertNotIn(identity, identities)
                    identities.add(identity)
                    self.assertRegex(item["employee_ref"], r"^[a-f0-9]{32}$")
                    self.assertNotIn("employee_external_id", item)
                    self.assertEqual(item["document_kind"], "SALE")
                    self.assertEqual(item["source_document_type"], "orderPosition")
                    self.assertEqual(item["source_status"], "Выдан")
                    self.assertEqual(item["expected_source_kind"], "SERVICE")
                    self.assertTrue(item["is_work"])
                    self.assertEqual(item["condition_type"], "NOT_APPLICABLE")
                    self.assertNotIn(
                        item["expected_analytics_category"],
                        {"IPAD_MAC", "PODS_WATCH_OTHER_DEVICE"},
                    )
                    self.assertNotIn(
                        item["target_analytics_category"],
                        {"IPAD_MAC", "PODS_WATCH_OTHER_DEVICE"},
                    )
                    self.assertGreater(Decimal(item["quantity"]), 0)
                    self.assertGreaterEqual(Decimal(item["net_amount"]), 0)
                    self.assertGreaterEqual(Decimal(item["cost_amount"]), 0)
                    analytics_changes = (
                        item["expected_analytics_category"]
                        != item["target_analytics_category"]
                    )
                    self.assertEqual(item["write_analytics_assignment"], analytics_changes)
                    if analytics_changes:
                        self.assertEqual(item["target_classification_version"], operation_id)
                    else:
                        self.assertEqual(
                            item["target_classification_version"],
                            item["expected_classification_version"],
                        )
                    if item["write_analytics_assignment"]:
                        analytics_by_product[item["product_external_id"]].add(
                            (item["target_analytics_category"], item["condition_type"])
                        )
                    if item["write_payroll_assignment"]:
                        payroll_by_product[item["product_external_id"]].add(
                            item["target_payroll_category"]
                        )

                self.assertTrue(all(len(values) == 1 for values in analytics_by_product.values()))
                self.assertTrue(all(len(values) == 1 for values in payroll_by_product.values()))
                self.assertEqual(
                    len(analytics_by_product), expected["analytics_assignment_count"]
                )
                self.assertEqual(
                    len(payroll_by_product), expected["payroll_assignment_count"]
                )

    def test_exact_approved_totals_are_pinned(self):
        expected = {
            "2026-01-magazin.json": (4, "27000.00", "11200.00", 0, 4),
            "2026-02-magazin.json": (2, "6500.00", "3500.00", 0, 2),
            "2026-03-magazin.json": (9, "41790.00", "24300.00", 3, 9),
        }
        for path, manifest in self.manifests():
            item_count, net, cost, analytics, payroll = expected[path.name]
            totals = manifest["expected"]
            self.assertEqual(
                (
                    totals["item_count"],
                    totals["net_amount"],
                    totals["cost_amount"],
                    totals["analytics_changed_item_count"],
                    totals["payroll_changed_item_count"],
                ),
                (item_count, net, cost, analytics, payroll),
            )


if __name__ == "__main__":
    unittest.main()

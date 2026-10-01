"""Local-only exporter regression tests; opt in to isolated Docker SQL integration."""
import collections
import json
import os
from pathlib import Path
import re
import subprocess
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/payroll-audit/export_catalog_snapshot.sh"
MARKER = "PAYROLL_SNAPSHOT_SQL"


def export_sql():
    return SCRIPT.read_text().split("<<'" + MARKER + "'\n", 1)[1].split("\n" + MARKER, 1)[0]


class SnapshotContractTest(unittest.TestCase):
    def test_shell_syntax(self):
        subprocess.run(["bash", "-n", str(SCRIPT)], check=True)

    def test_readonly_contract(self):
        sql = export_sql()
        self.assertTrue(sql.startswith("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;"))
        self.assertTrue(sql.rstrip().endswith("ROLLBACK;"))
        self.assertIn("current_user = 'store_backup_reader'", sql)
        self.assertNotRegex(sql, r"(?im)^\s*(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|COPY|COMMIT)\b")

    def test_shell_hardening(self):
        script = SCRIPT.read_text()
        for required in ("set +x", "umask 077", "env -i", "PGSSLMODE=verify-full",
                         "PGOPTIONS='-c default_transaction_read_only=on'",
                         "ON_ERROR_STOP=1", "snapshot.jsonl.partial", "sha256sum"):
            self.assertIn(required, script)
        self.assertNotRegex(script, r"(?m)^\s*source\s")


@unittest.skipUnless(os.environ.get("RUN_PAYROLL_SNAPSHOT_SQL_TESTS") == "1",
                     "set RUN_PAYROLL_SNAPSHOT_SQL_TESTS=1 for local Docker SQL tests")
class SnapshotDatabaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = "payroll-snapshot-test-" + uuid.uuid4().hex[:12]
        subprocess.run(["docker", "run", "--detach", "--rm", "--network", "none",
                        "--name", cls.container, "-e", "POSTGRES_HOST_AUTH_METHOD=trust",
                        "postgres:16-alpine"], check=True, stdout=subprocess.DEVNULL)
        cls.addClassCleanup(lambda: subprocess.run(
            ["docker", "stop", "--time", "1", cls.container],
            check=True, stdout=subprocess.DEVNULL))
        for _ in range(100):
            # The image's bootstrap server accepts Unix sockets, then shuts down.
            # TCP is ready only when the final server starts.
            ready = subprocess.run(["docker", "exec", cls.container, "pg_isready",
                                    "-h", "127.0.0.1", "-U", "postgres"],
                                   capture_output=True)
            if ready.returncode == 0:
                break
            time.sleep(.1)
        else:
            raise AssertionError("Local PostgreSQL did not become ready")
        result = cls.psql("CREATE SCHEMA app;", role="postgres")
        if result.returncode:
            raise AssertionError(result.stderr)
        migrations = sorted((ROOT / "backend/src/main/resources/db/migration").glob("V*__*.sql"),
                            key=lambda p: tuple(map(int, p.name.split("__")[0][1:].split("."))))
        for migration in migrations:
            result = cls.psql(migration.read_text(), role="postgres")
            if result.returncode:
                raise AssertionError(migration.name + ": " + result.stderr[-1200:])
        fixture = """
SET search_path = app, public;
INSERT INTO employees (source_system, external_id, full_name, metadata)
VALUES ('MANUAL', 'export-employee', 'PRIVATE_PERSON_SENTINEL', '{"secret":"PRIVATE_PAYLOAD_SENTINEL"}');
INSERT INTO products (source_system, external_id, code, name, source_kind, metadata)
VALUES ('MANUAL', 'export-product', 'export-001', E'Test "quoted"\\nproduct', 'PRODUCT',
        '{"secret":"PRIVATE_PAYLOAD_SENTINEL"}');
INSERT INTO stores (source_system, name) VALUES ('MANUAL', 'Synthetic export store');
INSERT INTO sync_runs (source_system, trigger_type, sync_scope, status, finished_at, error_summary)
VALUES ('MANUAL', 'MANUAL', 'FULL', 'SUCCESS', now(), 'PRIVATE_ERROR_SENTINEL');
INSERT INTO sales_documents (source_system, external_id, store_id, document_kind,
 source_document_type, occurred_at, business_date, net_amount, cost_amount, last_sync_run_id)
SELECT 'MANUAL', 'export-sale', s.id, 'SALE', 'sale', now(), '2026-09-01', 100, 60, r.id
FROM stores s, sync_runs r WHERE s.name='Synthetic export store';
INSERT INTO sales_document_items (sales_document_id, external_id, product_id, product_name_snapshot,
 analytics_category_id, condition_type_snapshot, quantity, unit_price, gross_amount, net_amount,
 cost_amount, cost_quality)
SELECT d.id, 'line-1', p.id, p.name, c.id, 'UNKNOWN', 1, 100, 100, 100, 60, 'KNOWN'
FROM sales_documents d, products p, analytics_categories c
WHERE d.external_id='export-sale' AND p.external_id='export-product' AND c.code='UNMAPPED';
INSERT INTO sales_documents (source_system, external_id, store_id, original_document_id, document_kind,
 source_document_type, occurred_at, business_date, net_amount, cost_amount, last_sync_run_id, is_deleted)
SELECT 'MANUAL', 'export-return', store_id, id, 'RETURN', 'return', now(), '2026-09-02',
 100, 60, last_sync_run_id, true FROM sales_documents WHERE external_id='export-sale';
INSERT INTO sales_document_items (sales_document_id, external_id, original_item_id, product_id,
 product_name_snapshot, analytics_category_id, condition_type_snapshot, quantity, unit_price,
 gross_amount, net_amount, cost_amount, cost_quality, is_deleted)
SELECT d.id, 'line-1', i.id, i.product_id, i.product_name_snapshot, i.analytics_category_id,
 'UNKNOWN', 1, 100, 100, 100, 60, 'KNOWN', true
FROM sales_documents d, sales_document_items i WHERE d.external_id='export-return';
CREATE ROLE store_backup_reader LOGIN;
GRANT USAGE ON SCHEMA app TO store_backup_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA app TO store_backup_reader;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA app FROM PUBLIC;
"""
        result = cls.psql(fixture, role="postgres")
        if result.returncode:
            raise AssertionError(result.stderr[-1800:])
        cls.snapshot = cls.psql(export_sql())
        if cls.snapshot.returncode:
            raise AssertionError(cls.snapshot.stderr[-1800:])
        cls.records = [json.loads(line) for line in cls.snapshot.stdout.splitlines()]

    @classmethod
    def psql(cls, sql, role="store_backup_reader"):
        return subprocess.run(["docker", "exec", "-i", "-e", "PGOPTIONS=-c search_path=app,public",
                               cls.container, "psql", "-X", "-qAt",
                               "-v", "ON_ERROR_STOP=1", "-U", role, "-d", "postgres"],
                              input=sql, capture_output=True, text=True)

    def test_manifests_and_snapshot_boundaries(self):
        self.assertEqual(self.records[0]["kind"], "snapshot_start")
        self.assertEqual(self.records[-1]["kind"], "snapshot_end")
        self.assertEqual(self.records[0]["snapshot_at"], self.records[-1]["snapshot_at"])
        self.assertEqual(self.records[-1]["read_only"], "on")
        counts = collections.Counter(row["kind"] for row in self.records)
        manifests = [r for r in self.records if r["kind"] == "table_manifest"]
        self.assertEqual(len(manifests), 20)
        for manifest in manifests:
            self.assertEqual(counts[manifest["table"]], manifest["row_count"])
        self.assertGreater(counts["payroll_resolver"], 0)

    def test_privacy_and_json_roundtrip(self):
        self.assertNotIn("PRIVATE_", self.snapshot.stdout)
        products = [r["data"] for r in self.records if r["kind"] == "products"]
        self.assertEqual(products[0]["name"], 'Test "quoted"\nproduct')
        self.assertNotIn("metadata", products[0])
        self.assertEqual(self.snapshot.stderr, "")

    def test_returns_and_deleted_facts_preserved(self):
        docs = [r["data"] for r in self.records if r["kind"] == "sales_documents"]
        sale = next(d for d in docs if d["document_kind"] == "SALE")
        ret = next(d for d in docs if d["document_kind"] == "RETURN")
        self.assertEqual(ret["original_document_id"], sale["id"])
        self.assertTrue(ret["is_deleted"])
        items = [r["data"] for r in self.records if r["kind"] == "sales_document_items"]
        ret_item = next(i for i in items if i["is_deleted"])
        self.assertIn(ret_item["original_item_id"], [i["id"] for i in items])
        self.assertEqual(ret_item["cost_amount"], 60)

    def test_wrong_role_rejected_before_rows(self):
        result = self.psql(export_sql(), role="postgres")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_reader_cannot_write(self):
        result = self.psql("BEGIN READ ONLY; UPDATE app.products SET name='invalid'; ROLLBACK;")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("read-only transaction", result.stderr)


if __name__ == "__main__":
    unittest.main()

import json
import os
from pathlib import Path
import re
import subprocess
import time
import unittest
import uuid

SCRIPT = Path(__file__).resolve().parents[1] / 'payroll-audit/export_return_source_links.sh'


def sql():
    return SCRIPT.read_text().split("<<'PAYROLL_RETURN_LINKS_SQL'\n")[1].split('\nPAYROLL_RETURN_LINKS_SQL')[0]


class ReturnExportContractTest(unittest.TestCase):
    def test_syntax_and_read_only(self):
        subprocess.run(['bash', '-n', str(SCRIPT)], check=True)
        self.assertTrue(sql().startswith('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;'))
        self.assertTrue(sql().rstrip().endswith('ROLLBACK;'))
        self.assertNotRegex(sql(), r'(?im)^\s*(INSERT|UPDATE|DELETE|ALTER|DROP|CREATE|COMMIT|COPY)\b')

    def test_whitelist_scope_and_transport(self):
        script = SCRIPT.read_text()
        self.assertEqual(len(re.findall(r"\('[0-9a-f]{24}'\)", sql())), 6)
        for guard in ['store_backup_reader', 'env -i', 'PGSSLMODE=verify-full', 'umask 077',
                      'snapshot.jsonl.partial', 'ON_ERROR_STOP=1', 'sha256sum']:
            self.assertIn(guard, script)
        self.assertNotIn('curl ', script)
        self.assertNotIn('to_jsonb', sql())


@unittest.skipUnless(os.environ.get('RUN_RETURN_LINKS_SQL_TESTS') == '1', 'local Docker opt-in')
class ReturnExportSqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = 'payroll-return-source-test-' + uuid.uuid4().hex[:12]
        subprocess.run(['docker', 'run', '--detach', '--rm', '--network', 'none', '--name',
                        cls.container, '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', 'postgres:16-alpine'],
                       check=True, stdout=subprocess.DEVNULL)
        cls.addClassCleanup(lambda: subprocess.run(['docker', 'stop', '--time', '1', cls.container],
                                                  check=True, stdout=subprocess.DEVNULL))
        for _ in range(100):
            if subprocess.run(['docker', 'exec', cls.container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres'],
                              capture_output=True).returncode == 0:
                break
            time.sleep(.1)
        else:
            raise AssertionError('Isolated PostgreSQL unavailable')
        fixture = """
CREATE SCHEMA app;
CREATE TABLE app.sales_documents(id text, external_id text, connection_id uuid, store_id text,
 document_kind text, business_date date, is_deleted boolean, original_document_id text, employee_id text);
CREATE TABLE app.sales_document_items(id text, external_id text, sales_document_id text,
 is_deleted boolean, original_item_id text, product_id text, quantity numeric, net_amount numeric, cost_amount numeric);
CREATE TABLE app.raw_record_versions(id text, connection_id uuid, store_id text, entity_type text,
 external_id text, payload jsonb, first_seen_at timestamptz, last_seen_at timestamptz, payload_policy_version int);
INSERT INTO app.sales_documents VALUES
 ('r','6957a26f214c1120750ba83e','dc622e79-8a8e-4813-9266-8a567483a0eb','s','RETURN','2026-01-02',false,null,null),
 ('p','source-parent','dc622e79-8a8e-4813-9266-8a567483a0eb','s','SALE','2025-12-20',false,null,'e');
INSERT INTO app.sales_document_items VALUES
 ('ri','return-item','r',false,null,'product',1,100,40),
 ('pi','source-item','p',false,null,'product',1,100,40);
INSERT INTO app.raw_record_versions VALUES
 ('raw','dc622e79-8a8e-4813-9266-8a567483a0eb','s','RETURN_DOCUMENT','6957a26f214c1120750ba83e',
 '{"private":"PRIVATE_SENTINEL","detail":{"parentDocument":{"id":"source-parent"},"positions":[{"positionId":"return-item","salePositionId":"source-item","nomenclatureId":"source-product","private":"PRIVATE_SENTINEL"}]}}',
 '2026-01-02Z','2026-01-02Z',1);
CREATE ROLE store_backup_reader LOGIN;
GRANT USAGE ON SCHEMA app TO store_backup_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA app TO store_backup_reader;
"""
        result = cls.run_sql(fixture, 'postgres')
        if result.returncode:
            raise AssertionError(result.stderr)

    @classmethod
    def run_sql(cls, source, role='store_backup_reader'):
        return subprocess.run(['docker', 'exec', '-i', cls.container, 'psql', '-h', '127.0.0.1', '-XqAt', '-U', role,
                               '-d', 'postgres', '-v', 'ON_ERROR_STOP=1'], input=source,
                              text=True, capture_output=True)

    def test_exact_links_missing_targets_privacy_and_role_guard(self):
        result = self.run_sql(sql())
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = [json.loads(line) for line in result.stdout.splitlines()]
        self.assertEqual(len(rows), 8)
        self.assertEqual(rows[0]['snapshot_at'], rows[-1]['snapshot_at'])
        self.assertNotIn('PRIVATE_SENTINEL', result.stdout)
        found = [r for r in rows if r.get('return_id') is not None]
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]['parent_id_in_db'], 'p')
        item = found[0]['items'][0]
        self.assertEqual(item['source_position_match_count'], 1)
        self.assertEqual(item['source_positions'][0]['original_item_in_db'], 'pi')
        self.assertNotEqual(self.run_sql(sql(), 'postgres').returncode, 0)


if __name__ == '__main__':
    unittest.main()

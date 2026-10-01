import json
import os
from pathlib import Path
import subprocess
import time
import unittest
import uuid


SCRIPT = Path(__file__).resolve().parents[1] / 'catalog-audit/export_migration_state.sh'


def sql():
    return SCRIPT.read_text().split("<<'CATALOG_MIGRATION_STATE_SQL'\n")[1].split(
        '\nCATALOG_MIGRATION_STATE_SQL')[0]


class MigrationStateContractTest(unittest.TestCase):
    def test_read_only_and_transport(self):
        subprocess.run(['bash', '-n', str(SCRIPT)], check=True)
        self.assertTrue(sql().startswith('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;'))
        self.assertTrue(sql().rstrip().endswith('ROLLBACK;'))
        self.assertNotRegex(sql(), r'(?im)^\s*(INSERT|UPDATE|DELETE|ALTER|DROP|CREATE|COMMIT|COPY)\b')
        for guard in ['env -i', 'PGSSLMODE=verify-full', 'umask 077', 'ON_ERROR_STOP=1',
                      'snapshot.jsonl.partial', 'sha256sum', 'store_backup_reader']:
            self.assertIn(guard, SCRIPT.read_text())

    def test_explicit_whitelist(self):
        self.assertNotIn('to_jsonb', sql())
        self.assertNotIn('SELECT *', sql())
        self.assertNotIn("'installed_by'", sql())
        self.assertNotIn('raw_record', sql())
        self.assertIn('FROM app.flyway_schema_history ORDER BY installed_rank', sql())


@unittest.skipUnless(os.environ.get('RUN_CATALOG_MIGRATION_STATE_SQL_TESTS') == '1',
                     'isolated local Docker opt-in')
class MigrationStateSqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = 'catalog-migration-state-test-' + uuid.uuid4().hex[:12]
        subprocess.run(['docker', 'run', '--detach', '--rm', '--network', 'none', '--name',
                        cls.container, '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', 'postgres:16-alpine'],
                       check=True, stdout=subprocess.DEVNULL)
        cls.addClassCleanup(lambda: subprocess.run(
            ['docker', 'stop', '--time', '1', cls.container], check=True, stdout=subprocess.DEVNULL))
        for _ in range(100):
            if subprocess.run(['docker', 'exec', cls.container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres'],
                              capture_output=True).returncode == 0:
                break
            time.sleep(.1)
        else:
            raise AssertionError('Isolated PostgreSQL unavailable')
        fixture = """
CREATE SCHEMA app;
CREATE TABLE app.flyway_schema_history(installed_rank integer, version text, type text,
 script text, checksum integer, installed_on timestamp, success boolean, installed_by text);
INSERT INTO app.flyway_schema_history VALUES
 (2,'2','SQL','V2__fixture.sql',-123,'2026-01-01',true,'PRIVATE_SENTINEL'),
 (1,'1','SQL','V1__fixture.sql',456,'2026-01-01',true,'PRIVATE_SENTINEL');
CREATE TABLE app.sales_document_items(is_deleted boolean, name text);
INSERT INTO app.sales_document_items VALUES(true,'PRIVATE_SENTINEL');
CREATE TABLE app.product_category_assignments(name text);
CREATE TABLE app.product_payroll_category_assignments(name text);
CREATE ROLE store_backup_reader LOGIN;
GRANT USAGE ON SCHEMA app TO store_backup_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA app TO store_backup_reader;
"""
        result = cls.run_sql(fixture, 'postgres')
        if result.returncode:
            raise AssertionError(result.stderr)

    @classmethod
    def run_sql(cls, source, role='store_backup_reader'):
        return subprocess.run(['docker', 'exec', '-i', cls.container, 'psql', '-h', '127.0.0.1',
                               '-XqAt', '-U', role, '-d', 'postgres', '-v', 'ON_ERROR_STOP=1'],
                              input=source, text=True, capture_output=True)

    def test_snapshot_order_deleted_rows_privacy_and_no_changes(self):
        result = self.run_sql(sql())
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = [json.loads(line) for line in result.stdout.splitlines()]
        self.assertEqual(len(rows), 5)
        self.assertEqual(rows[0]['snapshot_at'], rows[-1]['snapshot_at'])
        self.assertEqual(rows[-1]['history_rows'], 2)
        self.assertEqual([r['version'] for r in rows if r['kind'] == 'migration'], ['1', '2'])
        self.assertTrue(rows[3]['sales_document_items'])
        self.assertFalse(rows[3]['product_category_assignments'])
        self.assertFalse(rows[3]['product_payroll_category_assignments'])
        self.assertNotIn('PRIVATE_SENTINEL', result.stdout)
        self.assertEqual(self.run_sql('SELECT count(*) FROM app.flyway_schema_history;').stdout.strip(), '2')

    def test_wrong_role_and_missing_permission_fail_closed(self):
        self.assertNotEqual(self.run_sql(sql(), 'postgres').returncode, 0)
        self.assertEqual(self.run_sql(
            'REVOKE SELECT ON app.flyway_schema_history FROM store_backup_reader;',
            'postgres').returncode, 0)
        try:
            result = self.run_sql(sql())
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('snapshot_end', result.stdout)
        finally:
            self.assertEqual(self.run_sql(
                'GRANT SELECT ON app.flyway_schema_history TO store_backup_reader;',
                'postgres').returncode, 0)


if __name__ == '__main__':
    unittest.main()

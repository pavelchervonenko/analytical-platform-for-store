"""Offline accessory-role adapter checks using only synthetic catalog rows."""
import base64
import os
import re
import shutil
import subprocess
import tempfile
from copy import deepcopy
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/catalog-audit'))
from catalog_registry import REGISTRY  # noqa: E402
from probe_accessory_roles import JAVA_SOURCES, prepare_payload, parse_results  # noqa: E402


def report():
    return {'summary': {'connection_key': 'synthetic', 'category_registry_sha256': REGISTRY.sha256},
            'records': [{'source_kind': 'PRODUCT', 'code': 'synthetic-id', 'name': 'Watch charger\twith newline\n',
                         'source_group': 'synthetic', 'candidate_category': 'CHARGER_CABLE', 'status': 'OWNER_CONFIRMED',
                         'review_compatibility': ['APPLE_WATCH'],
                         'review_compatibility_source': 'OWNER_CONFIRMED'}]}


class CatalogAccessoryRoleAdapterTest(unittest.TestCase):
    def test_transport_does_not_invent_a_dated_exclusive_confirmation(self):
        source = report()
        original = deepcopy(source)
        payload = prepare_payload(source)
        cells = payload.rstrip('\n').split('\t')
        self.assertEqual(len(payload.splitlines()), 1)
        self.assertEqual(len(cells), 9)
        self.assertEqual(base64.b64decode(cells[4]).decode(), source['records'][0]['name'])
        self.assertEqual(cells[7], 'APPLE_WATCH')
        self.assertEqual(cells[8], 'OWNER_CONFIRMED')  # review status, not an authorized confirmation
        self.assertNotIn('EXCLUSIVE', payload)
        self.assertEqual(source, original)

    def test_stale_registry_is_rejected(self):
        source = report()
        source['summary']['category_registry_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'Registry changed'):
            prepare_payload(source)

    def test_duplicate_or_unknown_identity_and_empty_input_are_rejected(self):
        source = report()
        source['records'].append(deepcopy(source['records'][0]))
        with self.assertRaises(ValueError):
            prepare_payload(source)
        source = report()
        source['records'][0]['source_kind'] = 'UNKNOWN'
        with self.assertRaises(ValueError):
            prepare_payload(source)
        source['records'] = []
        with self.assertRaises(ValueError):
            prepare_payload(source)

    def test_malformed_targets_are_rejected_without_silent_deduplication(self):
        for targets in [['APPLE_WATCH', 'APPLE_WATCH'], ['APPLE_WATCH,IPHONE'], ['APPLE_WATCH\n'], [None], 'APPLE_WATCH']:
            source = report()
            source['records'][0]['review_compatibility'] = targets
            with self.subTest(targets=targets), self.assertRaises(ValueError):
                prepare_payload(source)

    def test_unknown_category_is_rejected(self):
        source = report()
        source['records'][0]['candidate_category'] = 'CHARGER_CABEL'
        with self.assertRaisesRegex(ValueError, 'Unknown catalog category'):
            prepare_payload(source)

    def test_result_preserves_owner_source_and_monetary_category(self):
        source = report()
        output = '0\tpolicy\tCHARGER_CABLE\tREVIEW_PRODUCT\t\tWATCH_EXCLUSIVITY_UNCONFIRMED\t\t' + '0' * 64 + '\n'
        rows = parse_results(output, source['records'])
        self.assertEqual(rows[0]['review_compatibility_source'], 'OWNER_CONFIRMED')
        self.assertEqual(rows[0]['review_compatibility'], ['APPLE_WATCH'])
        self.assertEqual(rows[0]['monetary_category'], 'CHARGER_CABLE')
        self.assertEqual(rows[0]['candidate_role'], '')

    def test_missing_rows_wrong_order_and_changed_money_category_fail_closed(self):
        source = report()
        good = '0\tpolicy\tCHARGER_CABLE\tREVIEW_PRODUCT\t\tREASON\t\t' + '0' * 64 + '\n'
        for output in ['', good.replace('0\tpolicy', '1\tpolicy'), good.replace('CHARGER_CABLE', 'POWER_BANK'),
                       good + good, '0\tpolicy\n']:
            with self.subTest(output=output), self.assertRaises(ValueError):
                parse_results(output, source['records'])


class CatalogAccessoryJavaReplayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java_home = os.environ.get('JAVA_HOME')
        javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
        java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
        if not javac or not java:
            raise unittest.SkipTest('JDK 21 required for current-source accessory replay')
        version = subprocess.run([javac, '-version'], capture_output=True, text=True, check=True)
        major = re.search(r'javac (\d+)', version.stdout + version.stderr)
        if not major or int(major.group(1)) < 21:
            raise unittest.SkipTest('JDK 21 required for current-source accessory replay')
        cls.directory = tempfile.TemporaryDirectory(prefix='catalog-accessory-test-')
        cls.addClassCleanup(cls.directory.cleanup)
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', cls.directory.name, *map(str, JAVA_SOURCES)],
                       check=True, capture_output=True, text=True)
        cls.command = [java, '-cp', os.pathsep.join([cls.directory.name,
            str(ROOT / 'backend/src/main/resources')]),
            'com.storeanalytics.product.service.CatalogAccessoryAuditProbe', '2026-09-30T12:00:00Z']

    def replay(self, source):
        return subprocess.run(self.command, input=prepare_payload(source), capture_output=True, text=True)

    def test_old_owner_singleton_is_not_upgraded_to_watch_exclusivity(self):
        source = report()
        process = self.replay(source)
        self.assertEqual(process.returncode, 0, process.stderr)
        row = parse_results(process.stdout, source['records'])[0]
        self.assertEqual(row['outcome'], 'REVIEW_PRODUCT')
        self.assertEqual(row['reason'], 'WATCH_EXCLUSIVITY_UNCONFIRMED')
        self.assertEqual(row['candidate_role'], '')

    def test_review_conflict_reaches_policy_instead_of_assigning_direct_role(self):
        source = report()
        source['records'][0].update(candidate_category='ACCESSORY_APPLE_WATCH', status='CONFLICT')
        process = self.replay(source)
        self.assertEqual(process.returncode, 0, process.stderr)
        row = parse_results(process.stdout, source['records'])[0]
        self.assertEqual(row['outcome'], 'REVIEW_PRODUCT')
        self.assertEqual(row['reason'], 'COMPATIBILITY_CONFLICT')

    def test_unknown_target_fails_in_java_instead_of_becoming_other(self):
        source = report()
        source['records'][0]['review_compatibility'] = ['WATCH_TYPO']
        process = self.replay(source)
        self.assertNotEqual(process.returncode, 0)
        self.assertEqual(process.stdout, '')


if __name__ == '__main__':
    unittest.main()

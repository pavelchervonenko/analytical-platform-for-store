"""Synthetic contract/parity checks; no database, provider access or catalog data."""
from dataclasses import FrozenInstanceError
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
AUDIT = ROOT / 'scripts/catalog-audit'
sys.path.insert(0, str(AUDIT))
from catalog_registry import CatalogRegistry, REGISTRY, REGISTRY_PATH, HEADER, VERSION  # noqa: E402
from lexicon_audit import Lexicon, apply_owner_decisions  # noqa: E402

CONTENT = REGISTRY_PATH.read_bytes().decode('utf-8')


def change_cell(code, field, value):
    lines = CONTENT.splitlines()
    for index, line in enumerate(lines[2:], 2):
        cells = line.split('\t')
        if cells[0] == code:
            cells[HEADER.index(field)] = value
            lines[index] = '\t'.join(cells)
            return '\n'.join(lines) + '\n'
    raise AssertionError('Missing synthetic target')


def invalid_documents():
    return {
        'version': CONTENT.replace('# ' + VERSION, '# unsupported-v0', 1),
        'header': CONTENT.replace('counts_as_phone', 'count_phone', 1),
        'duplicate_code': CONTENT + CONTENT.splitlines()[2] + '\n',
        'missing_columns': CONTENT + 'BROKEN\tBroken\n',
        'blank_row': CONTENT + '\n',
        'whitespace': change_cell('PACKAGING', 'name', ' Packaging'),
        'unknown_kind': change_cell('PACKAGING', 'category_kind', 'PACKAGING'),
        'unknown_scope': change_cell('PACKAGING', 'scope', 'DEPLOYED'),
        'invalid_boolean': change_cell('PACKAGING', 'counts_as_phone', '1'),
        'packaging_additional': change_cell('PACKAGING', 'counts_as_additional_revenue', 'true'),
        'accessory_device': change_cell('CHARGER_CABLE', 'counts_as_device', 'true'),
        'watch_phone': change_cell('WATCH_SAMSUNG', 'counts_as_phone', 'true'),
        'unknown_confirmed_condition': change_cell('IPHONE_NEW_ASIS', 'confirmed_conditions', 'UNKNOWN'),
        'duplicate_function': change_cell('GLASS_OTHER', 'confirmed_functions', 'SCREEN_GLASS|SCREEN_GLASS'),
        'unknown_device_type': change_cell('WATCH_APPLE', 'device_type', 'CLOCK'),
        'missing_device_mapping': change_cell('WATCH_SAMSUNG', 'device_brand_match', 'OTHER'),
        'missing_function': change_cell('TABLET_APPLE', 'confirmed_functions', ''),
        'duplicate_proposal': change_cell('TABLET_OTHER', 'proposal_keys', 'DEVICE:OTHER_TABLET|DEVICE:OTHER_TABLET'),
    }


class CatalogRegistryTest(unittest.TestCase):
    def test_registry_covers_matrix_and_preserves_legacy_and_deferred_categories(self):
        matrix = (ROOT / 'docs/maintenance/catalog-metrics-matrix.md').read_text()
        categories = matrix.split('## 2.', 1)[1].split('## 3.', 1)[0]
        codes = set(re.findall(r'^\| `([A-Z_]+)` \|', categories, re.M))
        self.assertEqual(codes, set(REGISTRY.definitions))
        self.assertEqual(len(codes), 54)
        self.assertEqual(REGISTRY.require('REPAIR_SERVICE').scope, 'DEFERRED')
        self.assertEqual(REGISTRY.require('DIAGNOSTICS_SERVICE').scope, 'DEFERRED')
        self.assertEqual(REGISTRY.require('IPAD_MAC').scope, 'LEGACY')
        self.assertEqual(REGISTRY.require('UNMAPPED').scope, 'TECHNICAL')

    def test_packaging_is_not_additional_and_watch_is_not_a_phone(self):
        packaging = REGISTRY.require('PACKAGING')
        self.assertEqual(packaging.category_kind, 'OTHER')
        self.assertFalse(packaging.counts_as_additional_revenue)
        self.assertFalse(packaging.counts_as_device)
        watch = REGISTRY.require('WATCH_SAMSUNG')
        self.assertTrue(watch.counts_as_device)
        self.assertFalse(watch.counts_as_phone)
        self.assertTrue(REGISTRY.require('CHARGER_CABLE').counts_as_additional_revenue)

    def test_shared_proposal_maps_preserve_current_shadow_rules(self):
        self.assertEqual(REGISTRY.proposal_categories('DEVICE')['IPAD'], 'TABLET_APPLE')
        self.assertEqual(REGISTRY.proposal_categories('SERVICE')['CLEANING'], 'SETUP_SERVICE')
        self.assertEqual(REGISTRY.proposal_categories('ITEM')['CHARGER'], 'CHARGER_CABLE')
        self.assertEqual(REGISTRY.proposal_categories('ITEM')['PACKAGING'], 'PACKAGING')
        self.assertEqual(REGISTRY.require('GLASS_OTHER').confirmed_functions, {'SCREEN_GLASS'})
        self.assertEqual(REGISTRY.require('SAMSUNG_NEW').confirmed_conditions, {'NEW'})

    def test_known_brand_and_unknown_brand_are_not_conflated(self):
        for brand in [None, '', '  ', 'unknown', 'OTHER']:
            self.assertIsNone(REGISTRY.device_category('WATCH', brand))
        for kind, brand, code in [('TABLET', 'Samsung', 'TABLET_OTHER'),
                                 ('LAPTOP', ' Apple ', 'LAPTOP_APPLE'),
                                 ('WATCH', 'Samsung', 'WATCH_SAMSUNG'),
                                 ('WATCH', 'Garmin', 'WATCH_OTHER')]:
            self.assertEqual(REGISTRY.device_category(kind, brand), code)
        with self.assertRaises(ValueError):
            REGISTRY.device_category('PHONE', 'Apple')

    def test_unknown_codes_and_mutation_are_rejected(self):
        for code in [None, '', 'CHARGER_CABEL', 'charger_cable', 'NEW_CATEGORY']:
            with self.subTest(code=code), self.assertRaises(ValueError):
                REGISTRY.require(code)
        with self.assertRaises(TypeError):
            REGISTRY.definitions['NEW'] = REGISTRY.require('PACKAGING')
        with self.assertRaises(FrozenInstanceError):
            REGISTRY.require('PACKAGING').name = 'Changed'

    def test_invalid_registry_documents_fail_closed(self):
        for name, document in invalid_documents().items():
            with self.subTest(name=name), self.assertRaises(ValueError):
                CatalogRegistry(document)

    def test_owner_decision_requires_known_category_and_supports_other_screen_glass(self):
        lexicon = Lexicon(json.loads((AUDIT / 'catalog_lexicon.json').read_text()), 'synthetic')
        name = 'Защитное стекло synthetic'
        row = {'source_kind': 'PRODUCT', 'code': 'synthetic', 'name': name,
               'source_group': '', 'legacy_category': '', **lexicon.propose(name)}
        decision = {'source_kind': 'PRODUCT', 'code': 'synthetic', 'expected_name': name,
                    'expected_group': '', 'category': 'GLASS_OTHER',
                    'confirmed_function': 'SCREEN_GLASS', 'resolved_reasons': []}
        def apply(value):
            return apply_owner_decisions([row], {'mode': 'OWNER_CONFIRMED_NOT_APPLIED',
                'connection_key': 'synthetic', 'decisions': [value]}, 'synthetic')
        result = apply(decision)[0]
        self.assertEqual(result['candidate_category'], 'GLASS_OTHER')
        self.assertIsNone(result['effective_assignment'])
        self.assertEqual(result['review_compatibility'], [])
        self.assertIn('TARGET_UNKNOWN', result['reasons'])
        with self.assertRaisesRegex(ValueError, 'Unknown catalog category'):
            apply({**decision, 'category': 'GLASS_OTHRE'})
        with self.assertRaisesRegex(ValueError, 'conflicts with category'):
            apply({**decision, 'confirmed_function': 'CASE'})


class CatalogRegistryJavaParityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java_home = os.environ.get('JAVA_HOME')
        javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
        java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
        if not javac or not java:
            raise unittest.SkipTest('JDK 21 required for Java/Python registry parity')
        version = subprocess.run([javac, '-version'], capture_output=True, text=True, check=True)
        major = re.search(r'javac (\d+)', version.stdout + version.stderr)
        if not major or int(major.group(1)) < 21:
            raise unittest.SkipTest('JDK 21 required for Java/Python registry parity')
        cls.directory = tempfile.TemporaryDirectory(prefix='catalog-registry-parity-')
        cls.addClassCleanup(cls.directory.cleanup)
        product = ROOT / 'backend/src/main/java/com/storeanalytics/product'
        sources = [product / 'model/AnalyticsCategoryKind.java', product / 'model/DeviceFamily.java',
                   product / 'model/ProductConditionType.java',
                   product / 'service/CatalogCategoryRegistry.java', AUDIT / 'CatalogRegistryAuditProbe.java']
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', cls.directory.name, *map(str, sources)],
                       capture_output=True, text=True, check=True)
        cls.command = [java, '-cp', os.pathsep.join([cls.directory.name,
            str(ROOT / 'backend/src/main/resources')]), 'com.storeanalytics.product.service.CatalogRegistryAuditProbe']

    def test_same_content_and_hash_in_java_and_python(self):
        result = subprocess.run(self.command, capture_output=True, text=True, check=True)
        lines = result.stdout.splitlines()
        self.assertEqual(lines[0], VERSION + '\t' + REGISTRY.sha256)
        expected = []
        for entry in REGISTRY.definitions.values():
            expected.append('\t'.join([entry.code, entry.name, entry.category_kind, entry.device_family,
                str(entry.counts_as_phone).lower(), str(entry.counts_as_device).lower(),
                str(entry.counts_as_additional_revenue).lower(), entry.scope, entry.device_type,
                entry.device_brand_match, '|'.join(sorted(entry.proposal_keys)),
                '|'.join(sorted(entry.confirmed_functions)), '|'.join(sorted(entry.confirmed_conditions))]))
        self.assertEqual(lines[1:], expected)

    def test_java_rejects_the_same_invalid_registry_documents(self):
        for name, document in invalid_documents().items():
            with self.subTest(name=name):
                result = subprocess.run([*self.command, '--stdin'], input=document,
                                        capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0, 'Java accepted ' + name)
                self.assertEqual(result.stdout, '')


if __name__ == '__main__':
    unittest.main()

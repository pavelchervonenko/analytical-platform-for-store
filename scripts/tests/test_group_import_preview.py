"""Synthetic-only regression tests for an offline, non-applicable group import preview."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
AUDIT = ROOT / 'scripts/catalog-audit'
sys.path.insert(0, str(AUDIT))
from prepare_group_import import fingerprint, prepare_rows  # noqa: E402
from lexicon_audit import Lexicon  # noqa: E402
from build_review import write_xlsx  # noqa: E402

DATA = json.loads((AUDIT / 'catalog_lexicon.json').read_text())
CONNECTION = 'livesklad-default'


def row(code='001', name='iPhone 15 Актив', group='/IPHONE (Б/У)'):
    return {'Код': code, 'Наименование': name, 'Полная группа': group, 'Название группы': group}


class GroupImportPreviewTest(unittest.TestCase):
    def prepare(self, products, services=None, connection=CONNECTION):
        exports = [('PRODUCT', products)]
        if services is not None:
            exports.append(('SERVICE', services))
        return prepare_rows(exports, connection, Lexicon(DATA, connection))

    def test_keeps_code_separate_from_provider_identity(self):
        result = self.prepare([row()])[0]
        self.assertEqual(result['source_code'], '001')
        self.assertIsNone(result['provider_product_id'])
        self.assertIsNone(result['database_product_id'])
        self.assertEqual(result['identity_status'], 'NEEDS_DATABASE_MATCH')
        self.assertEqual(result['group_action'], 'SET_CANDIDATE')

    def test_empty_group_is_never_a_delete(self):
        for value in ['', '   ']:
            with self.subTest(value=value):
                result = self.prepare([row(group=value)])[0]
                self.assertEqual(result['group_action'], 'KEEP_EXISTING')
                self.assertEqual(result['source_group_path'], value)
                self.assertIn('EMPTY_GROUP_NOT_A_CLEAR_COMMAND', result['group_review_reasons'])

    def test_missing_group_column_is_rejected_not_assumed_empty(self):
        data = row()
        del data['Полная группа']
        with self.assertRaisesRegex(ValueError, 'full source group'):
            self.prepare([data])

    def test_full_path_and_source_name_are_not_normalized_or_split(self):
        data = row(group='/AIRPODS/APPLE WATCH ')
        data['Название группы'] = 'AIRPODS/APPLE WATCH '
        result = self.prepare([data])[0]
        self.assertEqual(result['source_group_path'], data['Полная группа'])
        self.assertEqual(result['source_group_name'], data['Название группы'])
        self.assertNotIn('parent_id', result)

    def test_missing_leaf_name_is_reviewable(self):
        data = row()
        del data['Название группы']
        result = self.prepare([data])[0]
        self.assertIn('GROUP_NAME_MISSING', result['group_review_reasons'])

    def test_scope_code_and_kind_are_identity_not_name(self):
        result = self.prepare([row()], [row()])
        self.assertEqual(len(result), 2)
        self.assertNotEqual(result[0]['observation_sha256'], result[1]['observation_sha256'])
        other = self.prepare([row()], connection='another-connection')[0]
        self.assertNotEqual(result[0]['observation_sha256'], other['observation_sha256'])
        self.assertIsNone(other['group_condition_proposal'])
        result = self.prepare([row(), row(code='002')])
        self.assertEqual(len(result), 2)
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.prepare([row(), row(name='Different name')])

    def test_used_group_only_proposes_condition_for_actual_iphone(self):
        result = self.prepare([row()])[0]
        self.assertEqual(result['group_condition_proposal'],
                         {'category': 'IPHONE_USED', 'condition': 'USED', 'rule': 'owner_iphone_used_group'})
        for name in ['Чехол iPhone', 'iPad Air', 'MacBook Air', 'Samsung Galaxy S24',
                     'Ремонт iPhone', 'Apple Watch', 'iPhone 15 New', 'iPhone 15 ASIS']:
            with self.subTest(name=name):
                self.assertIsNone(self.prepare([row(name=name)])[0]['group_condition_proposal'])
        work = self.prepare([row()], [row()])[1]
        self.assertIsNone(work['group_condition_proposal'])

    def test_conflicts_and_nonmatching_groups_are_not_hidden(self):
        conflict = self.prepare([row(name='iPhone 15 New')])[0]
        self.assertIn('SOURCE_CONDITION_CONFLICT', conflict['classification_review_reasons'])
        for group in ['/IPHONE АКТИВ', '/IPHONE NEW', '/SAMSUNG Б/У', '/IPHONE (Б/У)/Чехлы']:
            with self.subTest(group=group):
                self.assertIsNone(self.prepare([row(group=group)])[0]['group_condition_proposal'])

    def test_invalid_inputs_fail_closed(self):
        for data in [[], [row(code='')], [row(code=' 001')], [row(name='')], [row(group=None)]]:
            with self.subTest(data=data):
                with self.assertRaises(ValueError):
                    self.prepare(data)
        with self.assertRaises(ValueError):
            prepare_rows([('UNKNOWN', [row()])], CONNECTION, Lexicon(DATA, CONNECTION))
        with self.assertRaises(ValueError):
            prepare_rows([('PRODUCT', [row()])], CONNECTION, Lexicon(DATA, 'other'))

    def test_cli_produces_non_applicable_preview_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory(prefix='group-preview-test-', dir=ROOT / 'outputs') as temp:
            folder = Path(temp)
            source = folder / 'products.xlsx'
            write_xlsx(source, [('Products', [['Код', 'Наименование', 'Полная группа'],
                                              ['001', '=synthetic text', '/Synthetic'],
                                              ['002', 'Synthetic product', '']])])
            output = folder / 'preview'
            command = [sys.executable, str(AUDIT / 'prepare_group_import.py'), '--products', str(source),
                       '--connection-key', CONNECTION, '--output', str(output)]
            completed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            path = output / 'source-group-import-preview.json'
            before = path.read_bytes()
            payload = json.loads(before)
            claimed_hash = payload.pop('preview_sha256')
            self.assertEqual(claimed_hash, fingerprint(payload))
            self.assertEqual(payload['mode'], 'PREVIEW_ONLY')
            self.assertEqual(payload['summary']['database_writes'], 0)
            self.assertFalse(payload['summary']['can_apply'])
            self.assertFalse(payload['summary']['live_database_verified'])
            self.assertIsNone(payload['effective_from'])
            self.assertIsNone(payload['export_observed_at'])
            self.assertNotIn('assignments', payload)
            self.assertEqual(payload['summary']['actions'], {'SET_CANDIDATE': 1, 'KEEP_EXISTING': 1})
            with zipfile.ZipFile(output / 'source-group-import-preview.xlsx') as book:
                ns = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
                sheet = ET.fromstring(book.read('xl/worksheets/sheet2.xml'))
                self.assertFalse(sheet.findall('.//m:f', ns))
                self.assertIsNotNone(sheet.find('m:autoFilter', ns))
                self.assertEqual(len(sheet.findall('m:sheetData/m:row', ns)), 3)
            again = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(again.returncode, 0)
            self.assertEqual(path.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()

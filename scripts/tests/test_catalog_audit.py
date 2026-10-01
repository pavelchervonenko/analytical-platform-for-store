"""Synthetic-only tests for local audit tooling; no business fixtures or database."""
import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts/catalog-audit' / (name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


REVIEW = module('build_review')
PROBE = module('probe_rules')


def record(**fields):
    result = dict(index=0, code='001', name='Synthetic phone', source_group='',
                  source_kind='PRODUCT', auto_category='UNMAPPED', auto_condition='UNKNOWN',
                  auto_rule='no-rule', initial_approved_category='', initial_approved_name_matches=None)
    result.update(fields)
    return result


class CatalogAuditTest(unittest.TestCase):
    def test_column_letters(self):
        self.assertEqual([REVIEW.excel_column(n) for n in (1, 26, 27, 52, 703)], ['A', 'Z', 'AA', 'AZ', 'AAA'])

    def test_csv_formula_injection(self):
        for value in ('=1+1', '+1', '-1', '@SUM(A1)', '  =1', '\t=1'):
            self.assertTrue(REVIEW.csv_cell(value).startswith("'"))
        self.assertEqual(REVIEW.csv_cell('001'), '001')

    def test_xlsx_roundtrip_preserves_strings_and_escapes(self):
        rows = [['Код', 'Наименование'], ['001', '=SUM(A1)'], ['002', '<phone> & "case"']]
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'synthetic.xlsx'
            REVIEW.write_xlsx(path, [('Товары', rows)])
            self.assertEqual(PROBE.read_xlsx(path), [dict(zip(rows[0], r)) for r in rows[1:]])
            with zipfile.ZipFile(path) as z:
                for name in z.namelist():
                    ET.fromstring(z.read(name))
                root = ET.fromstring(z.read('xl/worksheets/sheet1.xml'))
                self.assertFalse(root.findall('.//m:f', PROBE.NS))
                self.assertEqual(root.find('m:autoFilter', PROBE.NS).get('ref'), 'A1:B3')
                self.assertEqual(root.find('m:sheetViews/m:sheetView/m:pane', PROBE.NS).get('state'), 'frozen')

    def test_rejects_ambiguous_workbook(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'synthetic.xlsx'
            REVIEW.write_xlsx(path, [('One', [['Код', 'Наименование']]), ('Two', [['Код', 'Наименование']])])
            with self.assertRaisesRegex(ValueError, 'single'):
                PROBE.read_xlsx(path)

    def test_unknown_not_declared_confirmed(self):
        r = REVIEW.annotate([record()], {'overrides': {}})[0]
        self.assertEqual(r['suggested_category'], '')
        self.assertIn('НЕ ПРОВЕРЕНО', r['db_assignment'])
        self.assertIn('не выполнено', r['review_status'])

    def test_preserves_source_and_auto_decision(self):
        original = record(auto_category='SETUP_SERVICE', name='Synthetic iPhone Б/У ремонт')
        r = REVIEW.annotate([original], {'groups': [{'codes': ['001'], 'changes': {'suggested_category': 'IPHONE_USED'}}]})[0]
        self.assertEqual(r['auto_category'], 'SETUP_SERVICE')
        self.assertEqual(r['suggested_category'], 'IPHONE_USED')
        self.assertNotIn('suggested_category', original)

    def test_missing_proposal_code_fails(self):
        with self.assertRaisesRegex(ValueError, 'missing row'):
            REVIEW.annotate([record()], {'groups': [{'codes': ['999'], 'changes': {}}]})

    def test_duplicate_proposal_fails(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate proposal'):
            REVIEW.annotate([record()], {'groups': [{'codes': ['001', '001'], 'changes': {}}]})

    def test_current_hash_count_and_identity_guards(self):
        with tempfile.TemporaryDirectory() as temp:
            engine = Path(temp) / 'Engine.java'
            engine.write_text('synthetic')
            audit = Path(temp) / 'audit.json'
            summary = {'engine_sha256': hashlib.sha256(engine.read_bytes()).hexdigest(), 'source_counts': {'PRODUCT': 1}}
            audit.write_text(json.dumps({'summary': summary, 'records': [record()]}))
            self.assertEqual(len(REVIEW.load_audit(audit, engine)['records']), 1)
            summary['source_counts'] = {'PRODUCT': 2}
            audit.write_text(json.dumps({'summary': summary, 'records': [record()]}))
            with self.assertRaisesRegex(ValueError, 'counts'):
                REVIEW.load_audit(audit, engine)
            engine.write_text('changed')
            with self.assertRaisesRegex(ValueError, 'source changed'):
                REVIEW.load_audit(audit, engine)

    def test_duplicate_export_headers_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'synthetic.xlsx'
            REVIEW.write_xlsx(path, [('Data', [['Код', 'Код', 'Наименование'], ['1', '2', 'synthetic']])])
            with self.assertRaisesRegex(ValueError, 'Duplicate headers'):
                PROBE.read_xlsx(path)


if __name__ == '__main__':
    unittest.main()

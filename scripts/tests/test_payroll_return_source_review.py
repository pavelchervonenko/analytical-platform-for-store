import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'payroll-audit'))
from review_return_source_links import review, verify_export


class SourceLinksReviewTest(unittest.TestCase):
    def fixture(self):
        tables = dict(
            sales_documents=[dict(id='d', external_id='return', connection_id='c', store_id='s',
                                  document_kind='RETURN', business_date='2026-09-01', is_deleted=False,
                                  original_document_id=None, employee_id=None)],
            products=[dict(id='p', external_id='provider-product', code='test')],
            sales_document_items=[dict(id='i', sales_document_id='d', product_id='p', external_id='line',
                                       is_deleted=False, original_item_id=None, quantity='1.000',
                                       net_amount='100.00', cost_amount='40.00')])
        row = dict(kind='return_source_link', target_external_id='return', return_id='d',
                   return_kind='RETURN', return_deleted=False, original_document_id=None,
                   employee_id=None, connection_id='c', store_id='s', business_date='2026-09-01',
                   raw_id='raw', source_parent_id='parent', parent_id_in_db=None,
                   source_positions_are_array=True, items=[dict(
                       item_id='i', external_id='line', product_id='p', original_item_id=None,
                       item_deleted=False, quantity=1, net_amount=100, cost_amount=40,
                       source_position_match_count=1, source_positions=[dict(
                           original_position_external_id='original-line', product_external_id='provider-product')])])
        return row, tables

    def test_exact_links_are_not_recovery_permission(self):
        row, tables = self.fixture()
        result = review([row], tables)[0]
        self.assertEqual(result['issues'], ['ORIGINAL_SALE_NOT_IN_DATABASE'])
        self.assertTrue(result['amounts_unchanged'])
        self.assertTrue(result['source_product_matches'])
        self.assertFalse(result['source_is_live_api'])
        self.assertFalse(result['recovery_approved'])

    def test_identity_amount_scope_and_state_changes_rejected(self):
        row, tables = self.fixture()
        for key, value in [('store_id', 'other'), ('employee_id', 'other'), ('return_deleted', True),
                           ('original_document_id', 'new-parent'), ('target_external_id', 'other')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                review([dict(row, **{key: value})], tables)
        for key, value in [('product_id', 'other'), ('external_id', 'other'), ('quantity', 2),
                           ('net_amount', 101), ('cost_amount', None), ('item_deleted', True)]:
            bad = copy.deepcopy(row)
            bad['items'][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                review([bad], tables)
        with self.assertRaises(ValueError):
            review([row, row], tables)

    def test_missing_or_ambiguous_source_remains_unresolved(self):
        row, tables = self.fixture()
        row['items'][0]['source_position_match_count'] = 2
        self.assertIn('SOURCE_POSITION_MISSING_OR_AMBIGUOUS', review([row], tables)[0]['issues'])
        row['raw_id'] = None
        row['source_parent_id'] = None
        result = review([row], tables)[0]
        self.assertIn('NO_RETAINED_SOURCE', result['issues'])
        self.assertNotIn('ORIGINAL_SALE_NOT_IN_DATABASE', result['issues'])

    def test_source_product_mismatch_and_existing_parent_are_not_approved(self):
        row, tables = self.fixture()
        row['items'][0]['source_positions'][0]['product_external_id'] = 'other'
        self.assertIn('SOURCE_PRODUCT_MISMATCH', review([row], tables)[0]['issues'])
        row['parent_id_in_db'] = 'parent'
        result = review([row], tables)[0]
        self.assertIn('PARENT_STATE_REQUIRES_FRESH_PREFLIGHT', result['issues'])
        self.assertFalse(result['recovery_approved'])

    def test_export_checksum_boundary_count_and_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'snapshot.jsonl'
            rows = [dict(kind='snapshot_start', contract='payroll-return-source-links-v1',
                         role_guard=1, read_only='on', snapshot_at='time')]
            rows += [dict(kind='return_source_link') for _ in range(6)]
            rows += [dict(kind='snapshot_end', read_only='on', snapshot_at='time')]

            def save(data):
                path.write_text('\n'.join(json.dumps(r) for r in data) + '\n')
                path.with_name('snapshot.sha256').write_text(
                    hashlib.sha256(path.read_bytes()).hexdigest() + '  snapshot.jsonl\n')
                path.with_name('export.stderr').write_text('')

            save(rows)
            self.assertEqual(len(verify_export(path)[0]), 6)
            path.with_name('export.stderr').write_text('error')
            with self.assertRaises(ValueError):
                verify_export(path)
            for bad in [rows[:-1], [dict(rows[0], role_guard=0)] + rows[1:],
                        rows[:-1] + [dict(rows[-1], snapshot_at='other')]]:
                save(bad)
                with self.assertRaises(ValueError):
                    verify_export(path)
            save(rows)
            path.write_text(path.read_text() + '{}\n')
            with self.assertRaises(ValueError):
                verify_export(path)


if __name__ == '__main__':
    unittest.main()

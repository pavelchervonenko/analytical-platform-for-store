import copy
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib import error

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'payroll-audit'))
import audit_parent_sales_api as audit


class ParentApiAuditTest(unittest.TestCase):
    def fixture(self):
        target = dict(return_id='return', parent_id='parent', store_id='store', position_id='r-pos',
                      original_position_id='p-pos', product_id='product', quantity='1',
                      net_amount='100', cost_amount='40')
        position = dict(positionId='r-pos', salePositionId='p-pos', nomenclatureId='product',
                        count=1, soldPrice=100, purchasePriceSumm=40, name='PRIVATE_CUSTOMER_SENTINEL')
        ret = dict(id='return', type='saleReturn', date='2026-09-30T08:00:00.41Z',
                   shop={'id': 'store', 'name': 'PRIVATE_CUSTOMER_SENTINEL'},
                   parentDocument={'id': 'parent'}, positions=[position],
                   customer={'id': 'employee', 'name': 'PRIVATE_CUSTOMER_SENTINEL'})
        parent = dict(id='parent', type='sale', date='2026-09-29T08:00:00Z',
                      shop={'id': 'store'}, customer={'id': 'employee', 'name': 'PRIVATE_CUSTOMER_SENTINEL'},
                      positions=[dict(position, positionId='p-pos')])
        return target, ret, parent

    def test_correct_links_are_not_recovery_approval_or_active_status_proof(self):
        result = audit.assess(*self.fixture())
        self.assertEqual(result['issues'], [])
        self.assertFalse(result['recovery_approved'])
        self.assertFalse(result['explicit_parent_deletion_flag'])
        self.assertNotIn('PRIVATE_CUSTOMER_SENTINEL', json.dumps(result, default=str))
        self.assertEqual(result['parent']['positions'][0]['net_amount'], Decimal(100))

    def test_return_link_identity_amounts_changes_detected(self):
        target, ret, parent = self.fixture()
        for field, value, issue in [
                ('salePositionId', 'wrong', 'ORIGINAL_POSITION_LINK_CHANGED'),
                ('nomenclatureId', 'wrong', 'RETURN_PRODUCT_MISMATCH'),
                ('count', 2, 'RETURN_QUANTITY_CHANGED'),
                ('soldPrice', 99, 'RETURN_NET_AMOUNT_CHANGED'),
                ('purchasePriceSumm', None, 'RETURN_COST_AMOUNT_CHANGED')]:
            bad = copy.deepcopy(ret)
            bad['positions'][0][field] = value
            with self.subTest(field=field):
                self.assertIn(issue, audit.assess(target, bad, parent)['issues'])

    def test_parent_type_store_date_deletion_employee_are_checked(self):
        target, ret, parent = self.fixture()
        for field, value, issue in [
                ('type', 'order', 'PARENT_TYPE_MISMATCH'),
                ('id', 'wrong', 'PARENT_ID_MISMATCH'),
                ('shop', {'id': 'other'}, 'PARENT_STORE_MISMATCH'),
                ('date', '2026-10-01T00:00:00Z', 'PARENT_AFTER_RETURN'),
                ('date', 'not-a-date', 'PARENT_DATE_INVALID'),
                ('isDeleted', True, 'PARENT_DELETED'),
                ('customer', None, 'PARENT_EMPLOYEE_MISSING')]:
            with self.subTest(field=field):
                self.assertIn(issue, audit.assess(target, ret, dict(parent, **{field: value}))['issues'])

    def test_positions_duplicates_malformed_and_missing_are_not_silently_accepted(self):
        target, ret, parent = self.fixture()
        pos = parent['positions'][0]
        for positions, issue in [([], 'PARENT_POSITIONS_INVALID'),
                                 ([pos, pos], 'PARENT_DUPLICATE_POSITIONS'),
                                 ([pos, 'bad'], 'PARENT_POSITIONS_INVALID')]:
            self.assertIn(issue, audit.assess(target, ret, dict(parent, positions=positions))['issues'])

    def test_missing_and_nonfinite_money_not_zero(self):
        for value in (None, True, 'NaN', 'Infinity', 'not a number'):
            self.assertIsNone(audit.number(value))
        self.assertEqual(audit.number('0.00'), Decimal(0))

    def test_manifest_is_pinned_and_paths_cannot_escape_document_ids(self):
        targets = []
        for n in range(6):
            targets.append(dict.fromkeys(('return_id', 'parent_id', 'store_id', 'position_id',
                                           'original_position_id', 'product_id'), f'{n:024x}'))
        manifest = dict(contract='six-parent-sales-audit-v1', targets=targets)
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'targets.json'
            path.write_text(json.dumps(manifest))
            checksum = hashlib.sha256(path.read_bytes()).hexdigest()
            with patch.object(audit, 'MANIFEST_SHA256', checksum):
                self.assertEqual(len(audit.load_targets(path)['targets']), 6)
                path.write_text(path.read_text() + ' ')
                with self.assertRaises(audit.AuditError):
                    audit.load_targets(path)
            manifest['targets'][0]['parent_id'] = '../auth'
            path.write_text(json.dumps(manifest))
            with patch.object(audit, 'MANIFEST_SHA256', hashlib.sha256(path.read_bytes()).hexdigest()):
                with self.assertRaises(audit.AuditError):
                    audit.load_targets(path)

    def test_redirects_are_disabled(self):
        self.assertIsNone(audit.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.test'))

    def test_transport_limits_size_and_uses_explicit_methods(self):
        class Response:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, size): return b'{"data":{}}'
        class Opener:
            def open(self, req, timeout):
                self.method, self.timeout = req.get_method(), timeout
                return Response()
        opener = Opener()
        audit.fetch_json(opener, 'https://example.test/documents/id')
        self.assertEqual((opener.method, opener.timeout), ('GET', 30))
        audit.fetch_json(opener, 'https://example.test/auth', data=b'login=test')
        self.assertEqual(opener.method, 'POST')
        with patch.object(audit, 'MAX_BODY', 1), self.assertRaises(audit.AuditError):
            audit.fetch_json(opener, 'https://example.test/documents/id')


if __name__ == '__main__':
    unittest.main()

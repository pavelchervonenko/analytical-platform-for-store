import copy
from decimal import Decimal as D
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'payroll-audit'))
from reconcile_snapshot import shadow_month
from review_repair_confirmations import validate


class RepairConfirmationsTest(unittest.TestCase):
    def fixture(self):
        tables = dict(sales_document_items=[], sales_documents=[], products=[])
        rows = []
        for n in range(7):
            zero_cost = n < 4
            item = dict(id=str(n), external_id='i' + str(n), product_id='p' + str(n),
                        sales_document_id='d' + str(n), quantity='1', net_amount='100' if zero_cost else '0',
                        cost_amount='0' if zero_cost else '40', version=0, updated_at='now', is_deleted=False)
            doc = dict(id=item['sales_document_id'], external_id='sd' + str(n), connection_id='c',
                       store_id='s', business_date='2026-09-01', document_kind='SALE', is_deleted=False)
            product = dict(id=item['product_id'], external_id='sp' + str(n), code=str(n))
            tables['sales_document_items'].append(item)
            tables['sales_documents'].append(doc)
            tables['products'].append(product)
            rows.append(dict(item_id=item['id'], item_external_id=item['external_id'],
                             document_id=doc['id'], document_external_id=doc['external_id'],
                             connection_id='c', store_id='s', product_id=product['id'],
                             product_external_id=product['external_id'], code=product['code'],
                             business_date=doc['business_date'], observed_item_version=0,
                             observed_item_updated_at='now', quantity='1', net_amount=item['net_amount'],
                             cost_amount=item['cost_amount'], applied=False, effective_from=None,
                             historical_correction_approved=False, payroll_exclusion_approved=False,
                             decision_id='D-018' if zero_cost else 'D-019', full_cost_confirmed=zero_cost,
                             purpose='PAID_REPAIR' if zero_cost else 'FREE_OR_WARRANTY_REPAIR',
                             payroll_basis_status='CONFIRMED_ZERO_COST' if zero_cost
                             else 'CONFIRMED_ZERO_PRICE_ZERO_REWARD', payroll_category='PAID_REPAIR',
                             payroll_reward_base='0.00', analytics_cost_preserved=True))
        return dict(contract='payroll-repair-fact-confirmations-v1', scope='REVIEW_ONLY',
                    snapshot_sha256='snapshot', effective_from=None, applied=False, records=rows), tables

    def test_exact_seven_decisions(self):
        payload, tables = self.fixture()
        a, b = validate(payload, tables, 'snapshot')
        self.assertEqual((len(a), len(b)), (4, 3))

    def test_changed_identity_amount_version_or_authority_rejected(self):
        payload, tables = self.fixture()
        for key, value in [('store_id', 'other'), ('document_id', 'other'), ('product_id', 'other'),
                           ('observed_item_version', 1), ('quantity', '2'), ('net_amount', '1'),
                           ('applied', True), ('historical_correction_approved', True),
                           ('payroll_exclusion_approved', True), ('effective_from', '2026-10-01')]:
            with self.subTest(key=key):
                bad = copy.deepcopy(payload)
                bad['records'][0][key] = value
                with self.assertRaises(ValueError):
                    validate(bad, tables, 'snapshot')
        with self.assertRaises(ValueError):
            validate(payload, tables, 'other-snapshot')

    def test_duplicate_and_missing_confirmations_rejected(self):
        payload, tables = self.fixture()
        for rows in [payload['records'][:-1], payload['records'] + [payload['records'][0]]]:
            with self.assertRaises(ValueError):
                validate(dict(payload, records=rows), tables, 'snapshot')

    def test_free_purpose_does_not_confirm_full_cost_or_exclude_card(self):
        payload, tables = self.fixture()
        for key, value in [('full_cost_confirmed', True), ('payroll_category', 'EXCLUDE'),
                           ('payroll_basis_status', 'OWNER_RULE_REQUIRED'),
                           ('analytics_cost_preserved', False)]:
            bad = copy.deepcopy(payload)
            bad['records'][4][key] = value
            with self.assertRaises(ValueError):
                validate(bad, tables, 'snapshot')

    def fact(self, **changes):
        return dict(dict(item_id='x', expanded='PAID_REPAIR', business_date='2026-09-01',
                         analytically_excluded=False, sign=1, quantity=D(1),
                         net_amount=D(0), cost_amount=D(40)), **changes)

    def test_zero_reward_preserves_source_cost_and_other_negative_profit(self):
        free = self.fact()
        paid = self.fact(item_id='y', net_amount=D(10))
        self.assertEqual(shadow_month([free, paid], 'expanded', None, None)['PAID_REPAIR'], D(-70))
        result = shadow_month([free, paid], 'expanded', None, None, {'x'})
        self.assertEqual(result['PAID_REPAIR'], D(-30))
        self.assertEqual(result['revenue'], D(10))
        self.assertEqual(free['cost_amount'], D(40))

    def test_zero_reward_rejects_paid_return_other_role_and_missing_item(self):
        for changes in [dict(net_amount=D(10)), dict(sign=-1), dict(expanded='SERVICE'),
                        dict(item_id='y'), dict(analytically_excluded=True)]:
            with self.assertRaises(ValueError):
                shadow_month([self.fact(**changes)], 'expanded', None, None, {'x'})


if __name__ == '__main__':
    unittest.main()

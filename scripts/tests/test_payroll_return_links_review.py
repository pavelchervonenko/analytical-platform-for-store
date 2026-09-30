import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'payroll-audit'))
from review_return_links import candidates_for


class ReturnCandidatesTest(unittest.TestCase):
    def setUp(self):
        self.returned = dict(product_id='p', quantity='1', net_amount='100.00', cost_amount='40')
        self.return_doc = dict(connection_id='c', store_id='s', occurred_at='2026-09-30T12:00:00+03:00')
        self.sale = dict(id='sale', external_id='source-sale', is_deleted=False,
                         document_kind='SALE', connection_id='c', store_id='s',
                         occurred_at='2026-09-29T10:00:00+00:00', business_date='2026-09-29')
        self.item = dict(self.returned, id='item', external_id='source-item',
                         sales_document_id='sale', is_deleted=False, quantity='1.000')

    def evaluate(self, item=None, sale=None):
        return candidates_for(self.returned, self.return_doc, [item or self.item],
                              {'sale': sale or self.sale})

    def test_postgres_fraction_and_timezone_are_compared_as_instants(self):
        self.assertEqual(len(self.evaluate(sale=dict(
            self.sale, occurred_at='2026-09-30T08:59:59.41+00:00'
        ))), 1)
        self.assertEqual(self.evaluate(sale=dict(
            self.sale, occurred_at='2026-09-30T09:00:00.00001+00:00'
        )), [])

    def test_matching_amounts_are_not_confirmed_link(self):
        row = self.evaluate()[0]
        self.assertTrue(row['same_quantity_net_known_cost'])
        self.assertFalse(row['link_confirmed'])

    def test_excludes_other_identity_store_future_deleted_and_returns(self):
        for field, value in [('connection_id', 'other'), ('store_id', 'other'),
                             ('is_deleted', True), ('document_kind', 'RETURN'),
                             ('occurred_at', '2026-09-30T10:00:00+00:00')]:
            with self.subTest(field=field):
                self.assertEqual(self.evaluate(sale=dict(self.sale, **{field: value})), [])
        for field, value in [('product_id', 'other'), ('is_deleted', True)]:
            self.assertEqual(self.evaluate(item=dict(self.item, **{field: value})), [])

    def test_missing_cost_is_not_confirmed_zero(self):
        self.returned['cost_amount'] = None
        row = self.evaluate(item=dict(self.item, cost_amount=None))[0]
        self.assertTrue(row['same_quantity_net'])
        self.assertFalse(row['same_quantity_net_known_cost'])

    def test_partial_or_different_cost_returns_stay_candidates(self):
        row = self.evaluate(item=dict(self.item, quantity='2', cost_amount='50'))[0]
        self.assertFalse(row['same_quantity_net'])
        self.assertFalse(row['same_quantity_net_known_cost'])
        self.assertFalse(row['link_confirmed'])


if __name__ == '__main__':
    unittest.main()

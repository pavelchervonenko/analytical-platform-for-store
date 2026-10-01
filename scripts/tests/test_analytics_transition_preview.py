import copy
import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    'analytics_transition_preview', ROOT / 'scripts/catalog-audit/preview_analytics_transition.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AnalyticsTransitionPreviewTest(unittest.TestCase):
    def setUp(self):
        self.tables = {
            'integration_connections': [{'id': 'c', 'connection_key': 'catalog'}],
            'products': [dict(id='p', connection_id='c', external_id='external-p',
                              source_kind='PRODUCT', code='1', name='Notebook', version=2)],
            'sales_documents': [dict(id='d', connection_id='c', store_id='s',
                                     business_date='2026-09-10', document_kind='SALE', is_deleted=False)],
            'sales_document_items': [dict(id='i', sales_document_id='d', product_id='p',
                                          analytics_category_id='a', is_deleted=False, version=3)],
            'analytics_categories': [dict(id='a', code='IPAD_MAC')],
        }
        self.review = dict(summary={'connection_key': 'catalog'}, records=[dict(
            source_kind='PRODUCT', code='1', name='Notebook', candidate_category='LAPTOP_APPLE')])
        self.registry = {'LAPTOP_APPLE': {'scope': 'STANDARD'},
                         'TABLET_APPLE': {'scope': 'STANDARD'},
                         'REPAIR_SERVICE': {'scope': 'DEFERRED'}}

    def run_preview(self):
        return MODULE.preview(self.tables, self.review, self.registry, '2026-09-01', '2026-10-01')

    def test_candidate_is_not_confirmation_or_executable_manifest(self):
        before = copy.deepcopy(self.tables)
        result = self.run_preview()
        row = result['records'][0]
        self.assertEqual(row['reasons'], ['DECISION_EVIDENCE_REQUIRED'])
        self.assertEqual(row['decision_source'], 'LEXICON_PROPOSAL')
        self.assertTrue(row['requires_payroll_preflight'])
        self.assertEqual(row['expected_item_version'], 3)
        self.assertEqual(result['summary']['historical_facts_written'], 0)
        self.assertFalse(result['summary']['payroll_preflight_completed'])
        self.assertEqual(self.tables, before)

    def test_owner_decision_wins_but_does_not_skip_payroll_preflight(self):
        self.review['records'][0]['owner_confirmed_category'] = 'TABLET_APPLE'
        row = self.run_preview()['records'][0]
        self.assertEqual(row['candidate_category'], 'TABLET_APPLE')
        self.assertEqual(row['decision_source'], 'OWNER_CONFIRMED')
        self.assertTrue(row['requires_payroll_preflight'])

    def test_changed_name_and_source_kind_are_not_silently_rematched(self):
        self.tables['products'][0]['name'] = 'New notebook'
        row = self.run_preview()['records'][0]
        self.assertEqual(row['reasons'], ['PRODUCT_OBSERVATION_CHANGED'])
        self.assertIsNone(row['candidate_category'])
        self.tables['products'][0]['source_kind'] = 'SERVICE'
        self.assertIn('NO_REVIEWED_IDENTITY', self.run_preview()['records'][0]['reasons'])

    def test_deferred_and_unknown_categories_are_never_transition_targets(self):
        for target, reason in [('REPAIR_SERVICE', 'DEFERRED_CATEGORY_NOT_IN_RELEASE'),
                               ('invented', 'UNKNOWN_TARGET_CATEGORY')]:
            with self.subTest(target=target):
                self.review['records'][0]['owner_confirmed_category'] = target
                row = self.run_preview()['records'][0]
                self.assertIsNone(row['candidate_category'])
                self.assertFalse(row['category_change'])
                self.assertIn(reason, row['reasons'])

    def test_duplicate_codes_and_connections_fail_closed(self):
        self.tables['products'].append(dict(self.tables['products'][0], id='p2'))
        self.assertIn('AMBIGUOUS_CATALOG_IDENTITY', self.run_preview()['records'][0]['reasons'])
        self.review['summary']['connection_key'] = 'another'
        self.assertIn('CONNECTION_MISMATCH', self.run_preview()['records'][0]['reasons'])

    def test_duplicate_review_rows_are_rejected(self):
        self.review['records'].append(dict(self.review['records'][0]))
        with self.assertRaisesRegex(ValueError, 'Duplicate review identity'):
            self.run_preview()

    def test_date_interval_is_half_open_and_deleted_facts_are_ignored(self):
        for when, expected in [('2026-08-31', 0), ('2026-09-01', 1),
                               ('2026-09-30', 1), ('2026-10-01', 0)]:
            self.tables['sales_documents'][0]['business_date'] = when
            self.assertEqual(self.run_preview()['summary']['item_count'], expected)
        self.tables['sales_documents'][0]['business_date'] = '2026-09-10'
        self.tables['sales_document_items'][0]['is_deleted'] = True
        self.assertEqual(self.run_preview()['summary']['item_count'], 0)
        with self.assertRaises(ValueError):
            MODULE.preview(self.tables, self.review, self.registry, '2026-10-01', '2026-09-01')

    def test_return_outside_period_is_flagged_without_expanding_scope(self):
        self.tables['sales_documents'].append(dict(
            self.tables['sales_documents'][0], id='old', business_date='2026-08-20'))
        self.tables['sales_document_items'].append(dict(
            self.tables['sales_document_items'][0], id='old-item', sales_document_id='old'))
        self.tables['sales_documents'][0].update(document_kind='RETURN', original_document_id='old')
        self.tables['sales_document_items'][0]['original_item_id'] = 'old-item'
        result = self.run_preview()
        self.assertEqual(result['summary']['item_count'], 1)
        self.assertFalse(result['summary']['return_scope_expanded'])
        self.assertIn('ORIGINAL_OUTSIDE_SELECTED_PERIOD', result['records'][0]['reasons'])
        self.tables['sales_document_items'][0]['original_item_id'] = None
        self.assertIn('ORIGINAL_NOT_IN_SNAPSHOT_OR_NOT_LINKED', self.run_preview()['records'][0]['reasons'])

    def test_later_return_does_not_silently_expand_sale_correction_scope(self):
        self.tables['sales_documents'].append(dict(
            self.tables['sales_documents'][0], id='later', document_kind='RETURN',
            business_date='2026-10-01', original_document_id='d'))
        self.tables['sales_document_items'].append(dict(
            self.tables['sales_document_items'][0], id='returned', sales_document_id='later',
            original_item_id='i'))
        result = self.run_preview()
        self.assertEqual(result['summary']['item_count'], 1)
        self.assertIn('RELATED_RETURN_OUTSIDE_SELECTED_PERIOD', result['records'][0]['reasons'])
        self.assertFalse(result['summary']['return_scope_expanded'])


if __name__ == '__main__':
    unittest.main()

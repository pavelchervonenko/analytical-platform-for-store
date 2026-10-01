import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    'prospective_assignments', ROOT / 'scripts/catalog-audit/prepare_prospective_assignments.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ProspectiveAssignmentsTest(unittest.TestCase):
    def setUp(self):
        self.tables = dict(
            integration_connections=[dict(id='connection', connection_key='catalog')],
            products=[dict(id='product', connection_id='connection', source_kind='PRODUCT',
                           code='1', external_id='provider-1', name='Notebook', version=3, is_active=True)],
            analytics_categories=[dict(id='old', code='IPAD_MAC', is_active=True)],
            product_category_assignments=[dict(id='assignment', product_id='product',
                                               analytics_category_id='old', condition_type='UNKNOWN',
                                               valid_from='2026-01-01T00:00:00Z', valid_to=None)],
            product_payroll_category_assignments=[], source_product_groups=[],
        )
        self.review = dict(summary={'connection_key': 'catalog'}, records=[dict(
            source_kind='PRODUCT', code='1', name='Notebook', source_group='/Devices',
            candidate_category='LAPTOP_APPLE', reasons=[])])
        self.decisions = dict(mode='OWNER_CONFIRMED_NOT_APPLIED', connection_key='catalog', decisions=[])
        self.registry = {'LAPTOP_APPLE': {'scope': 'STANDARD'}, 'IPAD_MAC': {'scope': 'LEGACY'},
                         'REPAIR_SERVICE': {'scope': 'DEFERRED'}}

    def run_audit(self):
        return MODULE.audit(self.tables, self.review, self.decisions, self.registry,
                            '2026-09-30T12:00:00Z')

    def confirm(self):
        decision = dict(source_kind='PRODUCT', code='1', expected_name='Notebook',
                        expected_group='/Devices', category='LAPTOP_APPLE', resolved_reasons=[])
        self.decisions['decisions'].append(decision)
        self.review['records'][0].update(owner_decision=copy.deepcopy(decision),
                                          owner_confirmed_category='LAPTOP_APPLE')

    def test_proposals_are_not_owner_decisions_and_no_dates_or_writes_are_inferred(self):
        before = copy.deepcopy((self.tables, self.review, self.decisions))
        result = self.run_audit()
        row = result['records'][0]
        self.assertIn('DECISION_EVIDENCE_REQUIRED', row['reasons'])
        self.assertIsNone(row['confirmed_category'])
        self.assertEqual(row['candidate_action'], 'REQUIRES_DATED_REPLACEMENT')
        self.assertTrue(row['requires_payroll_preflight'])
        self.assertFalse(row['apply_allowed'])
        self.assertIsNone(result['summary']['effective_from'])
        self.assertFalse(result['summary']['release_ready'])
        self.assertEqual(result['summary']['historical_facts_written'], 0)
        self.assertEqual((self.tables, self.review, self.decisions), before)

    def test_exact_confirmation_retains_history_and_activation_guard(self):
        self.confirm()
        row = self.run_audit()['records'][0]
        self.assertEqual(row['confirmed_category'], 'LAPTOP_APPLE')
        self.assertEqual(row['assignment_history'], self.tables['product_category_assignments'])
        self.assertEqual(row['source_group_verification'], 'NOT_OBSERVED')
        self.assertIn('TARGET_NOT_IN_SNAPSHOT', row['reasons'])
        self.tables['analytics_categories'].append(dict(id='new', code='LAPTOP_APPLE', is_active=False))
        self.assertIn('TARGET_INACTIVE', self.run_audit()['records'][0]['reasons'])

    def test_confirmation_cannot_be_reconstructed_from_label_alone(self):
        self.confirm()
        self.review['records'][0]['owner_decision']['expected_name'] = 'Changed'
        with self.assertRaisesRegex(ValueError, 'disagree'):
            self.run_audit()
        self.review['records'][0].pop('owner_decision')
        with self.assertRaises(ValueError):
            self.run_audit()

    def test_changed_name_or_kind_or_connection_is_not_rematched(self):
        self.confirm()
        for field, value in [('name', 'Changed'), ('source_kind', 'SERVICE'), ('code', '2')]:
            with self.subTest(field=field):
                original = self.tables['products'][0][field]
                self.tables['products'][0][field] = value
                self.assertIsNone(self.run_audit()['records'][0]['confirmed_category'])
                self.tables['products'][0][field] = original
        self.tables['integration_connections'][0]['connection_key'] = 'another'
        result = self.run_audit()
        self.assertIsNone(result['records'][0]['confirmed_category'])
        self.assertEqual(result['summary']['source_only_count'], 1)

    def test_unknown_kind_is_a_hint_not_an_absent_product_or_confirmed_assignment(self):
        self.confirm()
        self.tables['products'][0]['source_kind'] = 'UNKNOWN'
        result = self.run_audit()
        row = result['records'][0]
        self.assertIn('SOURCE_KIND_UNVERIFIED', row['reasons'])
        self.assertIsNone(row['confirmed_category'])
        self.assertEqual(row['active_fact_count'], 0)
        self.assertTrue(row['possible_review_identity']['has_owner_decision'])
        self.assertEqual(result['summary']['source_only_count'], 0)
        self.assertEqual(result['summary']['missing_exact_review_identities'], 1)
        self.assertEqual(result['summary']['owner_decision_reconciliation'],
                         {'SOURCE_KIND_UNVERIFIED_OR_CONFLICT': 1})

    def test_duplicate_codes_and_missing_provider_id_are_guarded(self):
        self.confirm()
        self.tables['products'].append(dict(self.tables['products'][0], id='second', external_id='second'))
        self.assertTrue(all('AMBIGUOUS_CATALOG_IDENTITY' in r['reasons'] for r in self.run_audit()['records']))
        self.tables['products'].pop()
        self.tables['products'][0]['external_id'] = None
        self.assertIn('MISSING_PROVIDER_IDENTITY', self.run_audit()['records'][0]['reasons'])

    def test_duplicate_journal_or_review_fails_closed(self):
        self.confirm()
        self.decisions['decisions'].append(copy.deepcopy(self.decisions['decisions'][0]))
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.run_audit()
        self.decisions['decisions'].pop()
        self.review['records'].append(copy.deepcopy(self.review['records'][0]))
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.run_audit()

    def test_timestamp_offsets_and_half_open_intervals(self):
        first = self.tables['product_category_assignments'][0]
        first['valid_to'] = '2026-09-30T15:00:00+03:00'
        self.tables['product_category_assignments'].append(dict(first, id='next',
            valid_from='2026-09-30T12:00:00Z', valid_to=None))
        result = MODULE.histories(self.tables['product_category_assignments'],
                                  MODULE.instant('2026-09-30T12:00:00Z'))
        self.assertEqual(result['product'][1]['id'], 'next')
        with self.assertRaises(ValueError):
            MODULE.instant('2026-09-30T12:00:00')

    def test_overlapping_or_invalid_intervals_are_rejected(self):
        first = self.tables['product_category_assignments'][0]
        self.tables['product_category_assignments'].append(dict(first, id='overlap'))
        with self.assertRaisesRegex(ValueError, 'overlapping'):
            self.run_audit()
        self.tables['product_category_assignments'].pop()
        first['valid_to'] = first['valid_from']
        with self.assertRaises(ValueError):
            self.run_audit()

    def test_inactive_changed_group_and_future_assignments_are_visible(self):
        self.tables['products'][0].update(is_active=False, source_group_id='group')
        self.tables['source_product_groups'] = [dict(id='group', path='/Changed')]
        self.tables['product_category_assignments'][0]['valid_from'] = '2026-10-20T00:00:00Z'
        row = self.run_audit()['records'][0]
        self.assertIsNone(row['current_assignment_category'])
        self.assertTrue({'PRODUCT_INACTIVE', 'SOURCE_GROUP_CHANGED', 'FUTURE_ASSIGNMENT_EXISTS'} <= set(row['reasons']))

    def test_deferred_and_unknown_targets_are_not_rollout_candidates(self):
        for target in ('REPAIR_SERVICE', 'UNKNOWN_CODE'):
            self.review['records'][0]['candidate_category'] = target
            row = self.run_audit()['records'][0]
            self.assertIsNone(row['candidate_category'])
            self.assertEqual(row['candidate_action'], 'NO_VALIDATED_TARGET')

    def test_payroll_dates_are_preserved_without_inventing_time_or_new_roles(self):
        self.tables['product_payroll_category_assignments'] = [dict(
            id='payroll', product_id='product', valid_from='2026-01-01', valid_to=None,
            payroll_category_code='TECH_TIER_1')]
        row = self.run_audit()['records'][0]
        self.assertEqual(row['payroll_assignment_history'], self.tables['product_payroll_category_assignments'])
        self.assertTrue(row['requires_payroll_preflight'])

    def test_later_unsplit_service_policy_preserves_original_owner_evidence(self):
        self.tables['products'][0]['source_kind'] = 'SERVICE'
        row = self.review['records'][0]
        row.update(source_kind='SERVICE', candidate_category='REPAIR_SERVICE')
        decision = dict(source_kind='SERVICE', code='1', expected_name='Notebook',
                        expected_group='/Devices', category='REPAIR_SERVICE', resolved_reasons=[])
        self.decisions['decisions'].append(decision)
        row.update(owner_decision=copy.deepcopy(decision), owner_confirmed_category='REPAIR_SERVICE')
        self.registry['SETUP_SERVICE'] = {'scope': 'STANDARD'}
        result = self.run_audit()['records'][0]
        self.assertEqual(result['confirmed_category'], 'SETUP_SERVICE')
        self.assertEqual(result['original_candidate_category'], 'REPAIR_SERVICE')
        self.assertEqual(result['owner_decision']['category'], 'REPAIR_SERVICE')
        self.assertEqual(result['release_policy'], 'OWNER_SERVICE_UNSPLIT')
        self.assertFalse(result['apply_allowed'])

    def test_existing_assignment_and_unobserved_source_rows_are_separate(self):
        self.review['records'][0]['candidate_category'] = 'IPAD_MAC'
        self.review['records'].append(dict(self.review['records'][0], code='absent'))
        result = self.run_audit()
        self.assertEqual(result['records'][0]['candidate_action'], 'ALREADY_ASSIGNED')
        self.assertFalse(result['records'][0]['requires_payroll_preflight'])
        self.assertEqual(result['source_only'], [dict(source_kind='PRODUCT', code='absent')])


if __name__ == '__main__':
    unittest.main()

"""Synthetic-only checks for reviewed attributes; no live assignments or private fixtures."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
AUDIT_DIR = ROOT / 'scripts/catalog-audit'
sys.path.insert(0, str(AUDIT_DIR))
from lexicon_audit import Lexicon, apply_owner_decisions  # noqa: E402
from build_review import write_xlsx  # noqa: E402

DATA = json.loads((AUDIT_DIR / 'catalog_lexicon.json').read_text())
CONNECTION = 'livesklad-default'


class CatalogReviewProfileTest(unittest.TestCase):
    def setUp(self):
        self.lexicon = Lexicon(DATA, CONNECTION)

    def row(self, name, kind='PRODUCT', group='', code='synthetic'):
        return {'source_kind': kind, 'code': code, 'name': name, 'source_group': group,
                'legacy_category': '', **self.lexicon.propose(name, kind, group)}

    def decision(self, row, category, **attributes):
        return {'source_kind': row['source_kind'], 'code': row['code'],
                'expected_name': row['name'], 'expected_group': row['source_group'],
                'category': category, 'resolved_reasons': list(row['reasons']), **attributes}

    def overlay(self, rows, decisions=()):
        return apply_owner_decisions(rows, {'mode': 'OWNER_CONFIRMED_NOT_APPLIED',
            'connection_key': CONNECTION, 'decisions': list(decisions)}, CONNECTION)

    def test_confirmed_condition_has_priority_but_does_not_rewrite_observation(self):
        for name in ['Samsung S24 Актив', 'Samsung S24 New']:
            with self.subTest(name=name):
                row = self.row(name)
                before = copy.deepcopy(row)
                decision = self.decision(row, 'SAMSUNG_USED', confirmed_condition='USED')
                result = self.overlay([row], [decision])[0]
                self.assertEqual(row, before)
                self.assertEqual(result['condition'], row['condition'])
                self.assertEqual(result['condition_source'], row['condition_source'])
                self.assertEqual(result['owner_confirmed_condition'], 'USED')
                self.assertEqual(result['review_condition'], 'USED')
                self.assertEqual(result['review_condition_source'], 'OWNER_CONFIRMED')
                self.assertIsNone(result['effective_assignment'])
                self.assertEqual(result['owner_decision'], decision)

    def test_confirmed_function_preserves_keychain_type_and_unknown_work_type(self):
        for name, kind, category, function, original_types in [
            ('Keephone iWatch Magnetic брелок', 'PRODUCT', 'CHARGER_CABLE', 'CHARGER', ['KEYCHAIN']),
            ('Акб synthetic', 'SERVICE', 'REPAIR_SERVICE', 'REPAIR', []),
            ('Synthetic test card', 'PRODUCT', 'EXCLUDE', 'TEST_CARD', []),
            ('Чехол iPhone', 'PRODUCT', 'CASE_APPLE_IPHONE', 'CASE', ['CASE']),
        ]:
            with self.subTest(function=function):
                row = self.row(name, kind)
                result = self.overlay([row], [self.decision(row, category, confirmed_function=function)])[0]
                self.assertEqual(result['types'], original_types)
                self.assertEqual(result['review_types'], [function])
                self.assertEqual(result['review_types_source'], 'OWNER_CONFIRMED')
                self.assertEqual(result['owner_confirmed_function'], function)
                self.assertEqual(result['condition'], '')
                self.assertEqual(result['review_condition'], '')


    def test_confirmed_generic_tablet_film_keeps_type_distinct_from_brand(self):
        row = self.row('Защитная пленка для планшета')
        before = copy.deepcopy(row)
        decision = self.decision(row, 'PROTECTIVE_FILM', confirmed_function='FILM',
                                 confirmed_compatibility=['TABLET_GENERIC'])
        result = self.overlay([row], [decision])[0]
        self.assertEqual(row, before)
        self.assertEqual(result['candidate_category'], 'PROTECTIVE_FILM')
        self.assertEqual(result['review_compatibility'], ['TABLET_GENERIC'])
        self.assertEqual(result['review_compatibility_source'], 'OWNER_CONFIRMED')
        self.assertEqual(result['review_types'], ['FILM'])
        self.assertEqual(result['manufacturer_candidates'], [])
        self.assertEqual(result['review_condition'], '')
        self.assertIsNone(result['effective_assignment'])

    def test_generic_tablet_target_validation_and_unknown_film_are_not_bypassed(self):
        row = self.row('Защитная пленка')
        for target in ['TABLET_UNKNOWN_INVENTED', 'APPLE_TABLET_UNKNOWN']:
            with self.assertRaisesRegex(ValueError, 'Invalid confirmed compatibility'):
                self.overlay([row], [self.decision(row, 'PROTECTIVE_FILM',
                                                 confirmed_compatibility=[target])])
        result = self.overlay([row], [self.decision(row, 'PROTECTIVE_FILM', resolved_reasons=[])])[0]
        self.assertEqual(result['review_compatibility'], [])
        self.assertIn('TARGET_UNKNOWN', result['reasons'])

    def test_category_alone_never_infers_confirmed_attributes(self):
        row = self.row('Samsung S24 Актив')
        result = self.overlay([row], [self.decision(row, 'SAMSUNG_NEW')])[0]
        self.assertEqual(result['review_condition'], '')
        self.assertEqual(result['owner_confirmed_condition'], '')
        self.assertEqual(result['review_condition_source'], 'UNKNOWN')
        self.assertEqual(result['owner_confirmed_function'], '')
        self.assertEqual(result['review_types_source'], 'LEXICON')

    def test_observed_and_approved_group_provenance_is_preserved(self):
        for name, group, value, source in [
            ('iPhone 15 New', '', 'NEW', 'NAME'),
            ('iPhone 15 ASIS', '', 'ASIS', 'NAME'),
            ('iPhone 15 Актив', '/IPHONE (Б/У)', 'USED', 'OWNER_APPROVED_SOURCE_GROUP'),
            ('iPhone 15 Актив', '', '', 'UNKNOWN'),
        ]:
            with self.subTest(name=name, group=group):
                result = self.overlay([self.row(name, group=group)])[0]
                self.assertEqual((result['review_condition'], result['review_condition_source']), (value, source))
                self.assertEqual(result['owner_confirmed_condition'], '')

    def test_unknown_or_multiple_types_are_not_guessed(self):
        for name in ['Synthetic unknown item', 'iPad MacBook New', 'Чистка разъема и замена контакта']:
            with self.subTest(name=name):
                row = self.row(name)
                result = self.overlay([row])[0]
                self.assertEqual(result['review_types'], row['types'])
                self.assertEqual(result['reasons'], row['reasons'])
                self.assertEqual(result['status'], row['status'])
                self.assertEqual(result['review_types_source'], 'LEXICON' if row['types'] else 'UNKNOWN')

    def test_confirmed_compatibility_is_separate_and_manufacturer_is_not_inferred(self):
        row = self.row('Ремешок uBear Spark M/L')
        result = self.overlay([row], [self.decision(row, 'ACCESSORY_APPLE_WATCH',
                              confirmed_compatibility=['APPLE_WATCH'])])[0]
        self.assertEqual(result['compatibility'], [])
        self.assertEqual(result['review_compatibility'], ['APPLE_WATCH'])
        self.assertEqual(result['review_compatibility_source'], 'OWNER_CONFIRMED')
        self.assertEqual(result['manufacturer_candidates'], ['UBEAR'])
        result = self.overlay([self.row('Чехол iPhone')])[0]
        self.assertEqual(result['review_compatibility'], ['IPHONE'])
        self.assertEqual(result['review_compatibility_source'], 'LEXICON')

    def test_attributes_are_isolated_by_source_kind_and_code(self):
        row = self.row('Samsung S24 Актив')
        same_name = self.row(row['name'], code='another')
        same_code = self.row(row['name'], kind='SERVICE')
        result = self.overlay([row, same_name, same_code],
                              [self.decision(row, 'SAMSUNG_USED', confirmed_condition='USED')])
        self.assertEqual(result[0]['review_condition'], 'USED')
        for other in result[1:]:
            self.assertEqual(other['owner_confirmed_condition'], '')
            self.assertEqual(other['review_condition'], '')

    def test_unresolved_warnings_survive_confirmed_attributes(self):
        row = self.row('Samsung S24 Актив')
        row['reasons'].append('SOURCE_GROUP_CONFLICT')
        row['status'] = 'CONFLICT'
        decision = self.decision(row, 'SAMSUNG_USED', confirmed_condition='USED')
        decision['resolved_reasons'].remove('SOURCE_GROUP_CONFLICT')
        result = self.overlay([row], [decision])[0]
        self.assertEqual(result['review_condition'], 'USED')
        self.assertEqual(result['reasons'], ['SOURCE_GROUP_CONFLICT'])
        self.assertEqual(result['status'], 'CONFLICT')

    def test_profile_does_not_alias_observation_or_owner_decision_lists(self):
        row = self.row('Чехол iPhone')
        decision = self.decision(row, 'CASE_APPLE_IPHONE', confirmed_function='CASE',
                                 confirmed_compatibility=['IPHONE'])
        before_row, before_decision = copy.deepcopy(row), copy.deepcopy(decision)
        result = self.overlay([row], [decision])[0]
        result['review_types'].append('CHARGER')
        result['review_compatibility'].append('IPAD')
        result['owner_decision']['confirmed_compatibility'].append('MACBOOK')
        result['lexical_proposal']['reasons'].append('SYNTHETIC')
        result['types'].append('SYNTHETIC')
        self.assertEqual(row, before_row)
        self.assertEqual(decision, before_decision)
        self.assertEqual(result['owner_confirmed_compatibility'], ['IPHONE'])

    def test_invalid_confirmed_values_fail_closed(self):
        row = self.row('Samsung S24 Актив')
        for value in ['', None, [], {}, 1, True, 'new', 'BROKEN', 'UNKNOWN']:
            with self.subTest(condition=value):
                with self.assertRaisesRegex(ValueError, 'Invalid confirmed condition'):
                    self.overlay([row], [self.decision(row, 'SAMSUNG_NEW', confirmed_condition=value)])
        for value in ['', None, [], {}, 1, True, 'charger', 'UNRECOGNIZED']:
            with self.subTest(function=value):
                with self.assertRaisesRegex(ValueError, 'Invalid confirmed function'):
                    self.overlay([row], [self.decision(row, 'SAMSUNG_NEW', confirmed_function=value)])

    def test_confirmed_attributes_must_match_selected_category(self):
        row = self.row('Samsung S24 Актив')
        for category, condition in [('SAMSUNG_NEW', 'USED'), ('SAMSUNG_USED', 'ASIS'),
                                    ('IPHONE_NEW_ASIS', 'USED'), ('IPHONE_USED', 'NEW'),
                                    ('SAMSUNG_NEW', 'NOT_APPLICABLE')]:
            with self.subTest(category=category, condition=condition):
                with self.assertRaisesRegex(ValueError, 'conflicts with phone category'):
                    self.overlay([row], [self.decision(row, category, confirmed_condition=condition)])
        for category, function in [('POWER_BANK', 'CHARGER'), ('CHARGER_CABLE', 'CASE'),
                                   ('SETUP_SERVICE', 'REPAIR'), ('SAMSUNG_NEW', 'TEST_CARD')]:
            with self.subTest(category=category, function=function):
                with self.assertRaisesRegex(ValueError, 'function conflicts with category'):
                    self.overlay([row], [self.decision(row, category, confirmed_function=function)])

    def test_optional_not_applicable_and_asis_are_valid_not_defaults(self):
        for name, category, condition in [('iPhone 15 Актив', 'IPHONE_NEW_ASIS', 'ASIS'),
                                          ('Чехол iPhone', 'CASE_APPLE_IPHONE', 'NOT_APPLICABLE')]:
            result = self.overlay([self.row(name)], [self.decision(self.row(name), category,
                                   confirmed_condition=condition)])[0]
            self.assertEqual(result['review_condition'], condition)
            self.assertEqual(result['review_condition_source'], 'OWNER_CONFIRMED')

    def test_cli_json_and_xlsx_preserve_confirmations_and_provenance(self):
        with tempfile.TemporaryDirectory(prefix='review-profile-test-', dir=ROOT / 'outputs') as temp:
            folder = Path(temp)
            phone = self.row('Samsung S24 Актив', code='001')
            work = self.row('Акб synthetic', kind='SERVICE', code='001')
            headers = ['Код', 'Наименование', 'Полная группа']
            for path, row in [('products.xlsx', phone), ('services.xlsx', work)]:
                write_xlsx(folder / path, [('Source', [headers, [row['code'], row['name'], row['source_group']]])])
            decisions = {'mode': 'OWNER_CONFIRMED_NOT_APPLIED', 'connection_key': CONNECTION,
                         'decisions': [self.decision(phone, 'SAMSUNG_USED', confirmed_condition='USED'),
                                       self.decision(work, 'REPAIR_SERVICE', confirmed_function='REPAIR')]}
            manifest = folder / 'decisions.json'
            manifest.write_text(json.dumps(decisions))
            output = folder / 'review'
            command = [sys.executable, str(AUDIT_DIR / 'lexicon_audit.py'), '--products',
                       str(folder / 'products.xlsx'), '--services', str(folder / 'services.xlsx'),
                       '--connection-key', CONNECTION, '--owner-decisions', str(manifest), '--output', str(output)]
            completed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            report = json.loads((output / 'lexicon-proposals.json').read_text())
            self.assertEqual(report['summary']['review_profile_version'], 1)
            self.assertEqual(report['summary']['owner_confirmed_conditions'], 1)
            self.assertEqual(report['summary']['owner_confirmed_functions'], 1)
            self.assertEqual(report['summary']['assignments_written'], 0)
            self.assertFalse(report['summary']['live_database_verified'])
            ns = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
            with zipfile.ZipFile(output / 'catalog-lexicon-review.xlsx') as book:
                for sheet_index, record in [(3, report['records'][0]), (4, report['records'][1])]:
                    sheet = ET.fromstring(book.read(f'xl/worksheets/sheet{sheet_index}.xml'))
                    self.assertFalse(sheet.findall('.//m:f', ns))
                    self.assertIsNotNone(sheet.find('m:autoFilter', ns))
                    values = [[''.join(c.itertext()) for c in r.findall('m:c', ns)]
                              for r in sheet.findall('m:sheetData/m:row', ns)]
                    flat = dict(zip(values[0], values[1]))
                    self.assertEqual(flat['Состояние — подтверждено владельцем'], record['owner_confirmed_condition'])
                    self.assertEqual(flat['Функция — подтверждено владельцем'], record['owner_confirmed_function'])
                    self.assertEqual(flat['Состояние — итог проверки, НЕ БД'], record['review_condition'])
                    self.assertEqual(flat['Типы и функция — итог проверки, НЕ БД'], ', '.join(record['review_types']))
                    self.assertEqual(flat['Источник итогового состояния'], record['review_condition_source'])
                    self.assertEqual(flat['Источник итоговых типов'], record['review_types_source'])
            # Invalid input must fail before creating a partial report directory.
            decisions['decisions'][0]['confirmed_condition'] = 'NEW'
            manifest.write_text(json.dumps(decisions))
            rejected = folder / 'rejected'
            invalid = subprocess.run(command[:-1] + [str(rejected)], capture_output=True, text=True)
            self.assertNotEqual(invalid.returncode, 0)
            self.assertFalse(rejected.exists())

    def test_universal_case_confirmation_is_not_unknown_or_brand_specific(self):
        row = self.row('Чехол водонепроницаемый synthetic')
        original = copy.deepcopy(row)
        decision = self.decision(row, 'CASE_UNIVERSAL', confirmed_function='CASE',
                                 confirmed_compatibility=['PHONE_UNIVERSAL'])
        result = self.overlay([row], [decision])[0]
        self.assertEqual(result['candidate_category'], 'CASE_UNIVERSAL')
        self.assertEqual(result['review_compatibility'], ['PHONE_UNIVERSAL'])
        self.assertEqual(result['review_compatibility_source'], 'OWNER_CONFIRMED')
        self.assertEqual(result['review_types'], ['CASE'])
        self.assertNotIn('TARGET_UNKNOWN', result['reasons'])
        self.assertEqual(row, original)
        self.assertEqual(result['compatibility'], original['compatibility'])
        self.assertIsNone(result['effective_assignment'])

    def test_universal_case_requires_explicit_separate_compatibility(self):
        row = self.row('Чехол synthetic')
        for targets in [[], ['IPHONE'], ['IPHONE', 'SAMSUNG_PHONE'],
                        ['PHONE_UNIVERSAL', 'IPHONE']]:
            with self.subTest(targets=targets):
                with self.assertRaisesRegex(ValueError, 'requires explicit universal'):
                    self.overlay([row], [self.decision(row, 'CASE_UNIVERSAL',
                                          confirmed_compatibility=targets)])
        for category in ['OTHER_CASE', 'CASE_APPLE_IPHONE', 'CASE_SAMSUNG', 'PROTECTIVE_FILM']:
            with self.subTest(category=category):
                with self.assertRaisesRegex(ValueError, 'requires universal case category'):
                    self.overlay([row], [self.decision(row, category,
                                          confirmed_compatibility=['PHONE_UNIVERSAL'])])

    def test_universal_word_alone_does_not_create_owner_confirmation(self):
        row = self.row('Чехол универсальный водонепроницаемый synthetic')
        result = self.overlay([row])[0]
        self.assertEqual(result['owner_confirmed_compatibility'], [])
        self.assertEqual(result['owner_confirmed_category'], '')
        self.assertEqual(result['review_compatibility'], row['compatibility'])
        self.assertNotIn('PHONE_UNIVERSAL', result['review_compatibility'])

    def test_cli_universal_compatibility_is_visible_in_json_and_xlsx(self):
        with tempfile.TemporaryDirectory(prefix='universal-case-test-', dir=ROOT / 'outputs') as temp:
            folder = Path(temp)
            case = self.row('Чехол водонепроницаемый synthetic', code='synthetic-case')
            work = self.row('Настройка synthetic', kind='SERVICE', code='synthetic-work')
            headers = ['Код', 'Наименование', 'Полная группа']
            for filename, row in [('products.xlsx', case), ('services.xlsx', work)]:
                write_xlsx(folder / filename, [('Source', [headers,
                           [row['code'], row['name'], row['source_group']]])])
            manifest = folder / 'decisions.json'
            manifest.write_text(json.dumps({'mode': 'OWNER_CONFIRMED_NOT_APPLIED',
                'connection_key': CONNECTION, 'decisions': [self.decision(case, 'CASE_UNIVERSAL',
                confirmed_compatibility=['PHONE_UNIVERSAL'])]}))
            output = folder / 'review'
            completed = subprocess.run([sys.executable, str(AUDIT_DIR / 'lexicon_audit.py'),
                '--products', str(folder / 'products.xlsx'), '--services', str(folder / 'services.xlsx'),
                '--connection-key', CONNECTION, '--owner-decisions', str(manifest),
                '--output', str(output)], capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            report = json.loads((output / 'lexicon-proposals.json').read_text())
            self.assertEqual(report['records'][0]['review_compatibility'], ['PHONE_UNIVERSAL'])
            self.assertEqual(report['summary']['assignments_written'], 0)
            ns = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
            with zipfile.ZipFile(output / 'catalog-lexicon-review.xlsx') as book:
                sheet = ET.fromstring(book.read('xl/worksheets/sheet3.xml'))
                self.assertFalse(sheet.findall('.//m:f', ns))
                rows = [[''.join(c.itertext()) for c in r.findall('m:c', ns)]
                        for r in sheet.findall('m:sheetData/m:row', ns)]
                self.assertIn('PHONE_UNIVERSAL', rows[1])
                self.assertIn('CASE_UNIVERSAL', rows[1])

    def test_previous_review_cannot_be_reused_as_raw_observation(self):
        row = self.row('Samsung S24 Актив')
        decision = self.decision(row, 'SAMSUNG_USED', confirmed_condition='USED')
        for reviewed in [self.overlay([row]), self.overlay([row], [decision])]:
            with self.subTest(confirmed=bool(reviewed[0]['owner_confirmed_category'])):
                with self.assertRaisesRegex(ValueError, 'require raw observations'):
                    self.overlay(reviewed)


if __name__ == '__main__':
    unittest.main()

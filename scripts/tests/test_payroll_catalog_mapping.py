import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('payroll_catalog_mapping', ROOT/'scripts/payroll-audit/build_catalog_mapping.py')
mapping = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mapping)


def row(name, role, *types):
    return dict(source_kind='PRODUCT', code='example', name=name, role=role, review_types=list(types))


class PayrollCatalogMappingTest(unittest.TestCase):
    def test_device_levels(self):
        cases = [('iPhone 15 128GB ремонт', 'IPHONE', 'TECH_TIER_1'),
                 ('Samsung S24', 'SAMSUNG_PHONE', 'TECH_TIER_1'),
                 ('MacBook Pro', 'MACBOOK', 'TECH_TIER_1'),
                 ('iPad Pro', 'IPAD', 'TECH_TIER_2'),
                 ('Ноутбук Lenovo', 'OTHER_LAPTOP', 'TECH_TIER_2'),
                 ('Dyson HS08', 'HAIR_STYLER', 'TECH_TIER_1'),
                 ('Другой стайлер', 'HAIR_STYLER', 'TECH_TIER_2'),
                 ('PlayStation 5 Pro', 'CONSOLE', 'TECH_TIER_1'),
                 ('PlayStation 4', 'CONSOLE', 'TECH_TIER_2'),
                 ('PlayStation 50', 'CONSOLE', 'TECH_TIER_2'),
                 ('Samsung Watch', 'SAMSUNG_WATCH', 'TECH_TIER_2'),
                 ('AirPods Pro', 'APPLE_HEADPHONES', 'TECH_TIER_2'),
                 ('DJI Mic 2 + Charging Case', 'MICROPHONE', 'TECH_TIER_2')]
        for name, kind, expected in cases:
            with self.subTest(name=name):
                self.assertEqual(expected, mapping.proposal(row(name, 'DEVICE', kind))['proposed_payroll'])

    def test_accessory_is_not_mentioned_device(self):
        for name, kind in [('Чехол iPhone', 'CASE'), ('Кабель PS5', 'CHARGER'),
                           ('PlayStation 5 DualSense', 'GAMING_ACCESSORY'),
                           ('PlayStation Disc Drive', 'GAMING_ACCESSORY'),
                           ('Насадка Dyson', 'HOLDER'), ('Power Bank', 'POWER_BANK'),
                           ('Чехол неизвестная модель', 'CASE'), ('Плёнка', 'FILM')]:
            with self.subTest(name=name):
                self.assertEqual('ACCESSORY', mapping.proposal(row(name, 'ACCESSORY', kind))['proposed_payroll'])

    def test_contradictory_device_type_is_not_blindly_trusted(self):
        self.assertEqual('UNMAPPED', mapping.proposal(row('PlayStation 5 DualSense', 'DEVICE', 'CONSOLE'))['proposed_payroll'])

    def test_analytics_does_not_select_payroll(self):
        record = row('iPad Pro', 'DEVICE', 'IPAD')
        for analytics in ['IPAD_MAC', 'TABLET_APPLE', 'LAPTOP_APPLE', 'EXCLUDE']:
            record['candidate_category'] = analytics
            self.assertEqual('TECH_TIER_2', mapping.proposal(record)['proposed_payroll'])

    def test_service_and_repair_are_exclusive(self):
        for name, types, expected in [('Установка защитного покрытия', ['SETUP'], 'SERVICE'),
                                     ('Настройка MacBook', ['SETUP'], 'SERVICE'),
                                     ('Создание учетной записи Sony Play Station', ['SETUP'], 'SERVICE'),
                                     ('Замена аккумулятора', ['REPAIR'], 'PAID_REPAIR'),
                                     ('Акб', ['REPAIR'], 'PAID_REPAIR'),
                                     ('Чистка разъема и замена контакта', ['REPAIR','CLEANING'], 'PAID_REPAIR')]:
            with self.subTest(name=name):
                decision = mapping.proposal(row(name, 'SERVICE', *types))
                self.assertEqual(expected, decision['proposed_payroll'])
                if expected == 'PAID_REPAIR':
                    self.assertIn('не проверена', decision['cost_check'])

    def test_ambiguous_items_stay_unmapped_not_excluded(self):
        for name, role, kind, group in [('Фирменный пакет', 'ACCESSORY', 'PACKAGING', 'PACKAGING_POLICY'),
                ('тест', 'UNKNOWN', 'TEST_CARD', 'TEST_CARD_POLICY'),
                ('CARE+', 'COMMERCIAL', 'WARRANTY_GENERIC', 'WARRANTY_TERMS'),
                ('Microsoft Office', 'SERVICE', 'SETUP', 'SOFTWARE_LICENSE'),
                ('Прошивка', 'SERVICE', 'REPAIR', 'FIRMWARE_SCOPE'),
                ('Перепрошивка Samsung', 'SERVICE', 'REPAIR', 'FIRMWARE_SCOPE'),
                ('Замена стекла на камеру', 'SERVICE', 'REPAIR', 'GLASS_WORK_SCOPE'),
                ('Диагностика', 'SERVICE', 'DIAGNOSTICS', 'DIAGNOSTICS_SCOPE'),
                ('Чистка клавиатуры с разборкой', 'SERVICE', 'CLEANING', 'CLEANING_DISASSEMBLY')]:
            with self.subTest(name=name):
                decision = mapping.proposal(row(name, role, kind))
                self.assertEqual('UNMAPPED', decision['proposed_payroll'])
                self.assertEqual(group, decision['question_group'])
                self.assertEqual('NEEDS_DECISION', decision['proposal_status'])

    def test_same_base_bundle_not_split(self):
        self.assertEqual('ACCESSORY', mapping.proposal(row('Комплект чехол+стекло 13 Mini', 'ACCESSORY', 'MIXED_BUNDLE'))['proposed_payroll'])
        self.assertEqual('UNMAPPED', mapping.proposal(row('Комплект телефон+услуга', 'ACCESSORY', 'MIXED_BUNDLE'))['proposed_payroll'])

    def test_source_kind_is_part_of_key_and_duplicates_fail(self):
        a = dict(source_kind='PRODUCT', code='1')
        b = dict(source_kind='SERVICE', code='1')
        self.assertEqual(2, len(mapping.index_rows([a,b])))
        with self.assertRaises(ValueError):
            mapping.index_rows([a,a])

    def test_build_preserves_service_deferral_and_unknown_db(self):
        r = row('Замена аккумулятора', 'SERVICE', 'REPAIR')
        r.update(source_kind='SERVICE', source_group='', candidate_category='REPAIR_SERVICE', status='PROPOSAL')
        old = dict(r, auto_category='SETUP_SERVICE')
        probe = dict(r, before='SETUP_SERVICE', after='REPAIR_SERVICE', before_payroll='SERVICE', after_exists=False, after_payroll='UNMAPPED')
        result = mapping.build_records(dict(records=[r]), dict(records=[old]), dict(rows=[probe]))[0]
        self.assertEqual('SETUP_SERVICE', result['analytical_display'])
        self.assertEqual('PAID_REPAIR', result['proposed_payroll'])
        self.assertEqual('', result['effective_from'])
        self.assertFalse(result['approved'])
        self.assertIn('НЕ ПРОВЕРЕНО', result['current_db_payroll'])
        self.assertTrue(result['differs_from_old_fallback'])
        self.assertFalse(result['differs_from_candidate_fallback'])
        probe['name'] = 'Другая позиция'
        with self.assertRaises(ValueError):
            mapping.build_records(dict(records=[r]), dict(records=[old]), dict(rows=[probe]))

    def test_bounded_confirmation_changes_only_review(self):
        r = row('CARE', 'COMMERCIAL', 'WARRANTY_GENERIC')
        r.update(source_group='', candidate_category='WARRANTY_GENERIC', status='PROPOSAL')
        old = dict(r, auto_category='WARRANTY_GENERIC')
        probe = dict(r, before='WARRANTY_GENERIC', after='WARRANTY_GENERIC',
                     before_payroll='SERVICE', after_exists=True, after_payroll='SERVICE')
        records = mapping.build_records(dict(records=[r]), dict(records=[old]), dict(rows=[probe]))
        entry = dict(source_kind='PRODUCT', code='example', payroll_category='SERVICE',
                     expected_question_group='WARRANTY_TERMS', decision_id='D-TEST', reason='Explicit owner confirmation')
        decisions = dict(format_version=1, scope='REVIEW_ONLY', input_sha256={'source':'digest'},
                         effective_from=None, source_reply='Confirmed', confirmations=[entry])
        mapping.apply_confirmations(records, decisions, {'source':'digest'})
        self.assertEqual('SERVICE', records[0]['proposed_payroll'])
        self.assertEqual('OWNER_CONFIRMED', records[0]['proposal_status'])
        self.assertEqual('WARRANTY_TERMS', records[0]['resolved_question_group'])
        self.assertFalse(records[0]['differs_from_old_fallback'])
        self.assertFalse(records[0]['approved'])
        self.assertEqual('', records[0]['effective_from'])
        with self.assertRaises(ValueError):
            mapping.apply_confirmations(records, decisions, {'source':'changed'})

    def test_invalid_confirmation_batch_does_not_partially_apply(self):
        r = dict(source_kind='PRODUCT', code='a', proposal_status='NEEDS_DECISION',
                 question_group='WARRANTY_TERMS', proposed_payroll='UNMAPPED')
        entry = dict(source_kind='PRODUCT', code='a', payroll_category='SERVICE',
                     expected_question_group='WARRANTY_TERMS', decision_id='D-TEST')
        decisions = dict(format_version=1, scope='REVIEW_ONLY', input_sha256={},
                         effective_from=None, confirmations=[entry, entry])
        with self.assertRaises(ValueError):
            mapping.apply_confirmations([r], decisions, {})
        self.assertEqual('UNMAPPED', r['proposed_payroll'])
        decisions['confirmations'] = [dict(entry, code='missing')]
        with self.assertRaises(ValueError):
            mapping.apply_confirmations([r], decisions, {})
        decisions['confirmations'] = [dict(entry, payroll_category='EXCLUDE')]
        with self.assertRaises(ValueError):
            mapping.apply_confirmations([r], decisions, {})

    def test_exclude_requires_explicit_confirmation_and_preserves_analytics(self):
        r = row('Пакет', 'ACCESSORY', 'PACKAGING')
        r.update(source_group='', candidate_category='PACKAGING', status='PROPOSAL')
        old = dict(r, auto_category='UNMAPPED')
        probe = dict(r, before='UNMAPPED', after='PACKAGING',
                     before_payroll='UNMAPPED', after_exists=True, after_payroll='ACCESSORY')
        records = mapping.build_records(dict(records=[r]), dict(records=[old]), dict(rows=[probe]))
        entry = dict(source_kind='PRODUCT', code='example', payroll_category='EXCLUDE',
                     expected_question_group='PACKAGING_POLICY', decision_id='D-TEST',
                     reason='Explicit no direct reward', source_reply='Exclude this packaging')
        decisions = dict(format_version=1, scope='REVIEW_ONLY', input_sha256={},
                         effective_from=None, source_reply='Earlier confirmations', confirmations=[entry])
        for flag in (None, False, 'true'):
            entry['no_direct_reward_confirmed'] = flag
            with self.assertRaises(ValueError):
                mapping.apply_confirmations(records, decisions, {})
            self.assertEqual('UNMAPPED', records[0]['proposed_payroll'])
        entry['no_direct_reward_confirmed'] = True
        mapping.apply_confirmations(records, decisions, {})
        self.assertEqual('EXCLUDE', records[0]['proposed_payroll'])
        self.assertEqual('PACKAGING', records[0]['analytical_display'])
        self.assertEqual('PACKAGING', records[0]['analytical_candidate'])
        self.assertEqual('Exclude this packaging', records[0]['reviewer_answer'])
        self.assertEqual('', records[0]['effective_from'])
        self.assertFalse(records[0]['approved'])
        self.assertEqual(6, len(mapping.PAID_ROLES))
        self.assertNotIn('EXCLUDE', mapping.PAID_ROLES)
        self.assertIn('EXCLUDE', mapping.LABELS)
        self.assertIn('EXCLUDE', mapping.BASES)
        self.assertEqual('UNMAPPED', mapping.proposal(r)['proposed_payroll'])

    def test_excel_strings_and_filter(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'review.xlsx'
            mapping.write_xlsx(path, [('Проверка', [['Код','Название'], ['001','=1+1']])])
            with zipfile.ZipFile(path) as book:
                xml = ET.fromstring(book.read('xl/worksheets/sheet1.xml'))
                ns = {'m': mapping.write_xlsx.__globals__['NS']}
                self.assertFalse(xml.findall('.//m:f', ns))
                self.assertIsNotNone(xml.find('m:autoFilter', ns))
                self.assertEqual('001', xml.find(".//m:c[@r='A2']/m:is/m:t", ns).text)
        self.assertTrue(mapping.csv_cell('=1+1').startswith("'"))


if __name__ == '__main__':
    unittest.main()

"""Synthetic regression cases for the offline vocabulary; no private catalog fixtures."""
import copy
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
AUDIT_DIR = ROOT / 'scripts/catalog-audit'
sys.path.insert(0, str(AUDIT_DIR))
from lexicon_audit import Lexicon, apply_owner_decisions  # noqa: E402
from build_review import write_xlsx  # noqa: E402

DATA = json.loads((AUDIT_DIR / 'catalog_lexicon.json').read_text())


class CatalogLexiconTest(unittest.TestCase):
    def setUp(self):
        self.lexicon = Lexicon(DATA, 'livesklad-default')

    def test_every_rule_has_a_matching_positive_example(self):
        for rule in DATA['rules']:
            with self.subTest(rule=rule['id']):
                evidence = self.lexicon.extract(rule['example'], rule.get('input', 'name'),
                                                rule.get('required_family'), rule.get('required_source_kind'))[1]
                self.assertIn(rule['id'], [e['rule'] for e in evidence])

    def test_rule_validation(self):
        for change in ('duplicate', 'field', 'regex', 'mode', 'empty'):
            with self.subTest(change=change):
                data = copy.deepcopy(DATA)
                if change == 'duplicate':
                    data['rules'].append(data['rules'][0])
                elif change == 'field':
                    data['rules'][0]['field'] = 'category_assignment'
                elif change == 'regex':
                    data['rules'][0]['patterns'] = ['[']
                elif change == 'empty':
                    data['rules'][0]['patterns'] = []
                else:
                    data['mode'] = 'WRITE'
                with self.assertRaises((ValueError, re.error)):
                    Lexicon(data)

    def test_categories_and_context_regressions(self):
        cases = [
            ('iPhone 15 New', 'IPHONE_NEW_ASIS'), ('iPhone 15 ASIS+ New', 'IPHONE_NEW_ASIS'),
            ('Phone12 128GB Б/У', 'IPHONE_USED'), ('iPhone 14 Б/У ремонт', 'IPHONE_USED'),
            ('Samsung Galaxy S25FE NEW', 'SAMSUNG_NEW'), ('Samsung Galxy Z Fold 7 Б/У', 'SAMSUNG_USED'),
            ('iPad Air Б/У', 'TABLET_APPLE'), ('MacBook New брак по гарантии', 'LAPTOP_APPLE'),
            ('Lenovo ThinkPad New', 'LAPTOP_OTHER'), ('Samsung Galaxy Tab S10 New', 'TABLET_OTHER'),
            ('Apple Watch S11 42mm New', 'WATCH_APPLE'), ('Samsung Galaxy Watch 8', 'WATCH_SAMSUNG'),
            ('Huawei Watch New', 'WATCH_OTHER'), ('Смарт-часы Huawei', 'WATCH_OTHER'),
            ('Apple Watch Ultra 3 49mm Black Ocean Band New', 'WATCH_APPLE'),
            ('Garmin Vivoactive 6 Slate with Black Band', 'WATCH_OTHER'),
            ('Whoop 5.0', 'FITNESS_WEARABLE'), ('Fitbit Inspire', 'FITNESS_WEARABLE'),
            ('Apple EarPods USB-C', 'HEADPHONES_APPLE'), ('AirPods Pro', 'HEADPHONES_APPLE'),
            ('Galaxy Buds 3', 'HEADPHONES_SAMSUNG'), ('Наушники Samsung Buds 4', 'HEADPHONES_SAMSUNG'), ('Sony WF-1000XM6 New', 'HEADPHONES_OTHER'),
            ('Marshall Major 5', 'HEADPHONES_OTHER'), ('Наушники Samsung', 'HEADPHONES_SAMSUNG'),
            ('Ray-Ban Meta', 'SMART_GLASSES'), ('Dyson HS08', 'HAIR_STYLERS'),
            ('Фотоаппарат Instax Mini', 'CAMERAS'), ('Колонка JBL Flip 7', 'SPEAKERS'),
            ('Микрофон DJI Mic 2 (2TX + 1RX + Charging Case)', 'MICROPHONES'),
            ('PlayStation 5 Slim', 'GAME_CONSOLES'), ('PlayStation 5 DualSense', 'GAMING_ACCESSORIES'),
            ('Док-станция PS5 DualSense ChargingStation', 'GAMING_ACCESSORIES'),
            ('PlayStation Disc Drive White', 'GAMING_ACCESSORIES'),
            ('Чехол Keephone iPhone 17 Pro', 'CASE_APPLE_IPHONE'),
            ('Чехол Pitaka iPhone 17 Pro Milky Way Galaxy', 'CASE_APPLE_IPHONE'),
            ('Keephone X Crystal Samsung', 'CASE_SAMSUNG'), ('Чехол Samsung S25', 'CASE_SAMSUNG'),
            ('Чехол Keephone Hybrid Pro 17 Clear', 'CASE_APPLE_IPHONE'),
            ('Чeхол Magnetic', 'OTHER_CASE'), ('Чехол для ноутбука', 'CASE_OTHER_DEVICE'),
            ('Чехол MacBook', 'ACCESSORY_MAC'), ('Чехол iPad', 'ACCESSORY_IPAD'),
            ('Silicone Case VLP Charm Buds 4', 'CASE_OTHER_DEVICE'),
            ('Чехол AirPods', 'ACCESSORY_AIRPODS'), ('Чехол Samsung Watch', 'CASE_OTHER_DEVICE'),
            ('Стекло А35 А55 S25FE 5D', 'GLASS_SAMSUNG'), ('Стекло iPhone 17', 'GLASS_IPHONE'),
            ('Защита Kaмеры Keephone Samsung', 'GLASS_CAMERA_SAMSUNG'),
            ('Защитное стекло Keephone Camera Lens 17 Pro', 'GLASS_CAMERA_IPHONE'),
            ('Camera Film', 'GLASS_CAMERA_IPHONE'), ('Стекло 11/XR', 'GLASS_IPHONE'),
            ('Стекло SupGlass SG-11', 'GLASS_PHONE_UNRESOLVED'),
            ('Пленка для планшета', 'PROTECTIVE_FILM'), ('Пленка для iPad', 'ACCESSORY_IPAD'),
            ('Комплект чехол+стекло 13 Mini', 'OTHER_ACCESSORY_PRODUCT'),
            ('Apple Pencil Pro', 'ACCESSORY_IPAD'), ('Magic Mouse', 'ACCESSORY_MAC'),
            ('Наконечники Elago для Apple Pencil', 'ACCESSORY_IPAD'),
            ('Чехол клавиатура iPad', 'ACCESSORY_IPAD'),
            ('Ремешоу Watch Nike 41/45', 'ACCESSORY_APPLE_WATCH'),
            ('Ремешок Silicone 38-41mm', 'ACCESSORY_APPLE_WATCH'),
            ('Портативный аккумулятор Hoco 5000 mAh', 'POWER_BANK'),
            ('iPhone Air Magsafe Battery Pack', 'POWER_BANK'),
            ('Power Bank Baseus 10000mAh с кабелем', 'POWER_BANK'),
            ('Беспроводное зар. устройство VLP Lite Apple Watch', 'CHARGER_CABLE'),
            ('Переходник СЗУ Type-c 20W PD POWER ADAPTER', 'CHARGER_CABLE'),
            ('СЗУ Acefast 20W Set Cable USB-C', 'CHARGER_CABLE'),
            ('USB-C Lightning No Box', 'CHARGER_CABLE'), ('Кабель USB-C', 'CHARGER_CABLE'),
            ('Комплект VLP NEO 4in1', 'CHARGER_CABLE'), ('Станция 3 в 1 (Стоячая)', 'CHARGER_CABLE'),
            ('Переходник USB-C HDMI', 'OTHER_ACCESSORY_PRODUCT'), ('Кабель AUX', 'OTHER_ACCESSORY_PRODUCT'),
            ('Taggy Keephone', 'OTHER_ACCESSORY_PRODUCT'), ('Фирменный пакет магазина', 'PACKAGING'),
            ('Комплексная чистка устройства', 'SETUP_SERVICE'), ('Microsoft Office', 'SETUP_SERVICE'),
            ('Замена заднего стекла iPhone', 'REPAIR_SERVICE'), ('Диагностика устройства', 'DIAGNOSTICS_SERVICE'),
            ('Чистка разъема и замена контакта', 'REPAIR_SERVICE'),
            ('Future Store Elite Care', 'WARRANTY_GENERIC'), ('Моби Сфера Ultimate Care', 'PREMIUM_PROTECTION'),
        ]
        for name, expected in cases:
            with self.subTest(name=name):
                result = self.lexicon.propose(name)
                self.assertEqual(result['candidate_category'], expected)
                self.assertIsNone(result['effective_assignment'])


    def test_reviewed_garmin_models_are_watches_without_implicit_condition(self):
        for name in ['Garmin Forerunner 165 Music Whitestone',
                     'Garmin Vivoactive 6 Slate with Black Band',
                     'Garmin vívoactive 6']:
            with self.subTest(name=name):
                r = self.lexicon.propose(name)
                self.assertEqual(r['candidate_category'], 'WATCH_OTHER')
                self.assertEqual(r['types'], ['OTHER_WATCH'])
                self.assertEqual(r['manufacturer_candidates'], ['GARMIN'])
                self.assertEqual(r['role'], 'DEVICE')
                self.assertEqual(r['condition'], '')
                self.assertIn('CONDITION_UNKNOWN', r['reasons'])
                self.assertIsNone(r['effective_assignment'])

    def test_garmin_accessories_and_services_are_not_watches(self):
        for name in ['Ремешок для Garmin Vivoactive 6', 'Garmin Vivoactive 6 replacement band',
                     'Чехол Garmin Forerunner 165 Music', 'Кабель Garmin Vivoactive 6',
                     'Зарядка Garmin Forerunner 165 Music', 'Стекло Garmin Vivoactive 6',
                     'Настройка Garmin Vivoactive 6']:
            with self.subTest(name=name):
                r = self.lexicon.propose(name)
                self.assertNotEqual(r['candidate_category'], 'WATCH_OTHER')
                self.assertNotEqual(r['role'], 'DEVICE')
        r = self.lexicon.propose('Garmin Forerunner 165 Music', 'SERVICE')
        self.assertEqual(r['role'], 'SERVICE')
        self.assertNotEqual(r['candidate_category'], 'WATCH_OTHER')

    def test_garmin_rule_does_not_expand_to_other_models_or_hide_conflicts(self):
        for name in ['Garmin', 'Garmin Watch', 'Garmin Forerunner 1650 Music',
                     'Garmin Forerunner 165', 'Garmin Vivoactive 60',
                     'Garmin Vivoactive 7', 'Whoop 5.0', 'Fitbit Air']:
            with self.subTest(name=name):
                self.assertEqual(self.lexicon.propose(name)['candidate_category'], 'FITNESS_WEARABLE')
        r = self.lexicon.propose('Garmin Vivoactive 6 NEW Б/У')
        self.assertEqual(r['candidate_category'], '')
        self.assertIn('CONDITION_CONFLICT', r['reasons'])
        r = self.lexicon.propose('Garmin Vivoactive 6 Samsung Watch')
        self.assertEqual(r['candidate_category'], '')
        self.assertIn('DEVICE_TYPE_CONFLICT', r['reasons'])


    def test_generic_tablet_film_is_neutral_without_invented_brand(self):
        for name in ['Защитная пленка глянцевая для планшета',
                     'Плёнка полиуретановая Mietubl матовая для планшета',
                     'Protective film for tablet']:
            with self.subTest(name=name):
                r = self.lexicon.propose(name)
                self.assertEqual(r['candidate_category'], 'PROTECTIVE_FILM')
                self.assertEqual(r['compatibility'], ['TABLET_GENERIC'])
                self.assertEqual(r['role'], 'ACCESSORY')
                self.assertEqual(r['types'], ['FILM'])
                self.assertFalse({'APPLE', 'SAMSUNG'} & set(r['manufacturer_candidates']))
                self.assertEqual(r['condition'], '')
                self.assertIsNone(r['effective_assignment'])

    def test_tablet_film_refinement_preserves_explicit_ipad_and_other_item_types(self):
        for name in ['Пленка для iPad 10/11', 'Защитная пленка для планшета iPad']:
            r = self.lexicon.propose(name)
            self.assertEqual(r['candidate_category'], 'ACCESSORY_IPAD')
            self.assertEqual(r['compatibility'], ['IPAD'])
        self.assertEqual(self.lexicon.propose('Чехол для планшета')['candidate_category'],
                         'CASE_OTHER_DEVICE')
        for name in ['Стекло для планшета', 'Camera Film', 'Пленка Instax Mini']:
            self.assertNotEqual(self.lexicon.propose(name)['candidate_category'], 'PROTECTIVE_FILM')
        self.assertEqual(self.lexicon.propose('Планшет New')['candidate_category'], '')
        self.assertEqual(self.lexicon.propose('Samsung Galaxy Tab S10 New')['candidate_category'],
                         'TABLET_OTHER')
        self.assertNotEqual(self.lexicon.propose('Пленка для планшета', 'SERVICE')['candidate_category'],
                            'PROTECTIVE_FILM')

    def test_tablet_film_target_conflicts_and_unknown_targets_stay_visible(self):
        r = self.lexicon.propose('Пленка для планшета и iPhone')
        self.assertEqual(r['candidate_category'], '')
        self.assertIn('TARGET_CONFLICT', r['reasons'])
        r = self.lexicon.propose('Защитная пленка')
        self.assertEqual(r['compatibility'], [])
        self.assertIn('TARGET_UNKNOWN', r['reasons'])
        self.assertEqual(r['candidate_category'], 'FILM_PHONE')
        reversed_data = copy.deepcopy(DATA)
        reversed_data['rules'].reverse()
        other = Lexicon(reversed_data, 'livesklad-default')
        for name in ['Пленка для планшета', 'Пленка для iPad', 'Пленка для планшета и iPhone']:
            a, b = self.lexicon.propose(name), other.propose(name)
            a.pop('evidence')
            b.pop('evidence')
            self.assertEqual(a, b)

    def test_unknown_and_ambiguous_are_not_forced(self):
        for name in ['iPhone 15 (A) 100%', 'iPhone АКТИВ', 'iPhone НЕ АКТИВ',
                     'iPhone 15 New Б/У', 'Samsung Galaxy S25', 'Смарт-часы', 'Планшет',
                     'Ноутбук', 'Стилус', 'Защита камеры Hoco', 'Наушники EW74',
                     'Keephone iWatch Magnetic брелок', 'Дисплей для iPhone 16',
                     'Аккумулятор для iPhone 16', 'тест', 'Кабель HDMI USB-C зарядка',
                     'Чехол iPhone Samsung S25', 'iPad MacBook New',
                     'iPhone 16 New + чехол', 'PlayStation 5 Slim 1TB + DualSense']:
            with self.subTest(name=name):
                result = self.lexicon.propose(name)
                self.assertEqual(result['candidate_category'], '')
                self.assertNotEqual(result['status'], 'PROPOSAL')

    def test_no_implicit_new_or_other_brand(self):
        r = self.lexicon.propose('iPhone 17 (A) Актив', source_group='/IPHONE NEW')
        self.assertEqual(r['condition'], '')
        self.assertEqual(r['candidate_category'], '')
        self.assertIn('GRADE_NOT_CONDITION', r['reasons'])
        self.assertEqual(self.lexicon.propose('Планшет')['candidate_category'], '')
        self.assertEqual(self.lexicon.propose('iPhone 17 Asis New')['condition'], 'ASIS')

    def test_source_service_context(self):
        for name in ['Акб', 'Динамик', 'iPhone 17 New', 'Стекло iPhone']:
            r = self.lexicon.propose(name, 'SERVICE')
            self.assertEqual(r['role'], 'SERVICE')
            self.assertEqual(r['candidate_category'], '')
        self.assertEqual(self.lexicon.propose('Elite Care', 'SERVICE')['candidate_category'], 'WARRANTY_GENERIC')
        self.assertEqual(self.lexicon.propose('Чистка микрофона iPhone')['candidate_category'], 'SETUP_SERVICE')

    def test_compatibility_is_not_manufacturer(self):
        r = self.lexicon.propose('Чехол Baseus для Apple iPhone 17')
        self.assertEqual(r['manufacturer_candidates'], ['BASEUS'])
        self.assertEqual(r['compatibility'], ['IPHONE'])
        r = self.lexicon.propose('Беспроводное зар. устройство VLP Apple Watch')
        self.assertEqual(r['manufacturer_candidates'], ['VLP'])
        self.assertEqual(r['role'], 'ACCESSORY')
        self.assertEqual(self.lexicon.propose('Taggy Keephone')['manufacturer_candidates'], ['KEEPHONE'])

    def test_store_scoped_rules_never_leak(self):
        for key in [None, 'another-shop']:
            lexicon = Lexicon(DATA, key)
            for name in ['Ray Ban', 'Camera Film', 'Microsoft Office', 'Комплект VLP NEO', 'Elite Care']:
                evidence = lexicon.extract(name)[1]
                self.assertFalse(any(e['scope'] for e in evidence))
            self.assertNotEqual(lexicon.propose('Ray Ban')['candidate_category'], 'SMART_GLASSES')

    def test_rule_order_cannot_change_composed_decision(self):
        reversed_data = copy.deepcopy(DATA)
        reversed_data['rules'].reverse()
        other = Lexicon(reversed_data, 'livesklad-default')
        for name in ['iPhone 15 Б/У ремонт', 'Чехол Samsung S25', 'СЗУ Samsung', 'Galaxy Buds 3',
                     'MacBook New брак по гарантии', 'Чехол iPhone Samsung S25']:
            a, b = self.lexicon.propose(name), other.propose(name)
            a.pop('evidence')
            b.pop('evidence')
            self.assertEqual(a, b)

    def test_normalization_is_idempotent_and_keeps_condition_punctuation(self):
        n = self.lexicon.normalize('  Чeхол   iPhone Б/У  ')
        self.assertEqual(n, 'чехол iphone б/у')
        self.assertEqual(n, self.lexicon.normalize(n))
        for name in ['', '   ']:
            with self.assertRaises(ValueError):
                self.lexicon.propose(name)
        with self.assertRaises(ValueError):
            self.lexicon.propose('iPhone', 'UNKNOWN')

    def test_cli_audit_is_reproducible_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory(prefix='lexicon-test-', dir=ROOT / 'outputs') as temp:
            folder = Path(temp)
            products, services, output = folder / 'products.xlsx', folder / 'services.xlsx', folder / 'result'
            headers = ['Код', 'Наименование', 'Полная группа']
            write_xlsx(products, [('Товары', [headers, ['001', 'iPhone 16 New', '/IPHONE']])])
            write_xlsx(services, [('Работы', [headers, ['001', 'Замена дисплея', '/Ремонт']])])
            command = [sys.executable, str(AUDIT_DIR / 'lexicon_audit.py'),
                       '--products', str(products), '--services', str(services),
                       '--connection-key', 'livesklad-default', '--output', str(output)]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            payload = (output / 'lexicon-proposals.json').read_bytes()
            report = json.loads(payload)
            self.assertEqual(report['summary']['assignments_written'], 0)
            self.assertFalse(report['summary']['live_database_verified'])
            self.assertEqual(report['summary']['category_proposed'], 2)
            self.assertEqual(len({r['observation_sha256'] for r in report['records']}), 2)
            again = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(again.returncode, 0)
            self.assertEqual(payload, (output / 'lexicon-proposals.json').read_bytes())
            # A baseline from different source bytes must fail before creating outputs.
            baseline = folder / 'baseline.json'
            baseline.write_text(json.dumps({'summary': {'sources': {}}, 'records': []}))
            mismatch = subprocess.run(command[:-1] + [str(folder / 'other'), '--baseline', str(baseline)],
                                      capture_output=True, text=True)
            self.assertNotEqual(mismatch.returncode, 0)
            self.assertIn('fingerprints differ', mismatch.stderr)
            self.assertFalse((folder / 'other').exists())



    def test_exact_owner_confirmation_preserves_unknown_manufacturer(self):
        row = {'source_kind': 'PRODUCT', 'code': 'synthetic-1', 'name': 'Наушники EW synthetic',
               'source_group': '/Исходная группа', 'legacy_category': 'HEADPHONES_OTHER',
               **self.lexicon.propose('Наушники EW synthetic')}
        untouched = copy.deepcopy(row)
        data = {'mode': 'OWNER_CONFIRMED_NOT_APPLIED', 'connection_key': 'test',
                'decisions': [{'source_kind': 'PRODUCT', 'code': 'synthetic-1',
                               'expected_name': row['name'], 'expected_group': row['source_group'],
                               'category': 'HEADPHONES_OTHER',
                               'resolved_reasons': ['BRAND_UNRESOLVED', 'NO_CATEGORY_PROPOSAL']}]}
        result = apply_owner_decisions([row], data, 'test')[0]
        self.assertEqual(result['candidate_category'], 'HEADPHONES_OTHER')
        self.assertEqual(result['status'], 'OWNER_CONFIRMED')
        self.assertEqual(result['manufacturer_candidates'], [])
        self.assertIsNone(result['effective_assignment'])
        self.assertEqual(result['lexical_proposal']['candidate_category'], '')
        self.assertEqual(row, untouched)
        for mutation in ['connection', 'code', 'name', 'group', 'duplicate', 'reason', 'category']:
            with self.subTest(mutation=mutation):
                changed = copy.deepcopy(data)
                if mutation == 'connection':
                    changed['connection_key'] = 'other'
                elif mutation == 'duplicate':
                    changed['decisions'] *= 2
                else:
                    field = {'code': 'code', 'name': 'expected_name', 'group': 'expected_group',
                             'reason': 'resolved_reasons', 'category': 'category'}[mutation]
                    changed['decisions'][0][field] = ['UNSEEN_REASON'] if mutation == 'reason' else 'changed'
                with self.assertRaises(ValueError):
                    apply_owner_decisions([row], changed, 'test')
        other = dict(row, code='synthetic-2')
        result = apply_owner_decisions([row, other], data, 'test')
        self.assertEqual(result[1]['candidate_category'], '')
        self.assertEqual(result[1]['status'], 'NEEDS_REVIEW')



    def test_watch_airpods_split_does_not_split_chargers_or_devices(self):
        cases = [
            ('Чехол AirPods Pro', 'ACCESSORY_AIRPODS'),
            ('Чехол Apple Watch', 'ACCESSORY_APPLE_WATCH'),
            ('Стекло Apple Watch', 'ACCESSORY_APPLE_WATCH'),
            ('Ремешок Apple Watch', 'ACCESSORY_APPLE_WATCH'),
            ('Чехол AirPods Apple Watch', ''),
            ('Чехол EarPods', ''),
            ('Чехол AirPods EarPods', ''),
            ('Зарядка Apple Watch', 'CHARGER_CABLE'),
            ('Зарядка AirPods', 'CHARGER_CABLE'),
            ('Apple Watch New', 'WATCH_APPLE'),
            ('AirPods Pro', 'HEADPHONES_APPLE'),
            ('EarPods USB-C', 'HEADPHONES_APPLE'),
            ('Ремешок uBear Spark M/L', ''),
            ('Чехол Samsung Watch', 'CASE_OTHER_DEVICE'),
            ('Ремешок Samsung Watch', ''),
        ]
        for name, expected in cases:
            with self.subTest(name=name):
                self.assertEqual(self.lexicon.propose(name)['candidate_category'], expected)

    def test_owner_target_is_separate_from_name_evidence(self):
        row = {'source_kind': 'PRODUCT', 'code': 'synthetic-strap',
               'name': 'Ремешок uBear Spark M/L', 'source_group': '/Ремешки',
               'legacy_category': 'ACCESSORY_PODS_WATCH',
               **self.lexicon.propose('Ремешок uBear Spark M/L')}
        data = {'mode': 'OWNER_CONFIRMED_NOT_APPLIED', 'connection_key': 'test',
                'decisions': [{'source_kind': 'PRODUCT', 'code': row['code'],
                               'expected_name': row['name'], 'expected_group': row['source_group'],
                               'category': 'ACCESSORY_APPLE_WATCH', 'confirmed_compatibility': ['APPLE_WATCH'],
                               'resolved_reasons': ['TARGET_UNKNOWN', 'NO_CATEGORY_PROPOSAL']}]}
        result = apply_owner_decisions([row], data, 'test')[0]
        self.assertEqual(result['owner_confirmed_compatibility'], ['APPLE_WATCH'])
        self.assertEqual(result['compatibility'], [])
        self.assertEqual(result['manufacturer_candidates'], ['UBEAR'])
        self.assertEqual(result['status'], 'OWNER_CONFIRMED')
        for invalid in ['APPLE_WATCH', ['UNKNOWN'], ['APPLE_WATCH', 'APPLE_WATCH'], [None]]:
            with self.subTest(invalid=invalid):
                changed = copy.deepcopy(data)
                changed['decisions'][0]['confirmed_compatibility'] = invalid
                with self.assertRaises(ValueError):
                    apply_owner_decisions([row], changed, 'test')



    def test_approved_used_iphone_group_supplies_missing_condition(self):
        for group in ['/IPHONE (Б/У)', '/IPHONE 2 (Б/У)', 'iphone (б/у)']:
            for name in ['iPhone 15', 'iPhone 16 (A) 100%', 'iPhone 16 Актив', 'iPhone 16 НЕ АКТИВ']:
                with self.subTest(group=group, name=name):
                    result = self.lexicon.propose(name, 'PRODUCT', group)
                    self.assertEqual(result['candidate_category'], 'IPHONE_USED')
                    self.assertEqual(result['condition'], 'USED')
                    self.assertEqual(result['condition_source'], 'OWNER_APPROVED_SOURCE_GROUP')
                    self.assertNotIn('CONDITION_UNKNOWN', result['reasons'])
                    self.assertEqual([e['input'] for e in result['evidence']
                                      if e['rule'] == 'owner_iphone_used_group'], ['source_group'])

    def test_used_group_does_not_change_item_type_or_leak_to_other_scopes(self):
        group = '/IPHONE (Б/У)'
        for name, expected in [
            ('iPad Air', 'TABLET_APPLE'), ('MacBook Air', 'LAPTOP_APPLE'),
            ('Samsung Galaxy S25', ''), ('Apple Watch', 'WATCH_APPLE'),
            ('Чехол iPhone', 'CASE_APPLE_IPHONE'), ('Зарядка iPhone', 'CHARGER_CABLE'),
            ('Стекло iPhone', 'GLASS_IPHONE'), ('iPhone iPad', '')]:
            with self.subTest(name=name):
                result = self.lexicon.propose(name, 'PRODUCT', group)
                self.assertEqual(result['candidate_category'], expected)
                self.assertNotEqual(result['condition_source'], 'OWNER_APPROVED_SOURCE_GROUP')
        for group in ['/IPHONE АКТИВ', '/IPHONE NEW', '/SAMSUNG Б/У', '/IPHONE (Б/У)/Чехлы', '']:
            self.assertEqual(self.lexicon.propose('iPhone 16', 'PRODUCT', group)['candidate_category'], '')
        for key in [None, 'other-connection']:
            result = Lexicon(DATA, key).propose('iPhone 16', 'PRODUCT', '/IPHONE (Б/У)')
            self.assertEqual(result['candidate_category'], '')
        result = self.lexicon.propose('Замена дисплея iPhone', 'SERVICE', '/IPHONE (Б/У)')
        self.assertEqual(result['candidate_category'], 'REPAIR_SERVICE')
        self.assertEqual(result['condition'], '')

    def test_name_and_used_group_conflict_is_not_silently_resolved(self):
        for name in ['iPhone 16 New', 'iPhone 16 ASIS', 'iPhone 16 New Б/У']:
            with self.subTest(name=name):
                result = self.lexicon.propose(name, 'PRODUCT', '/IPHONE (Б/У)')
                self.assertEqual(result['candidate_category'], '')
                self.assertEqual(result['status'], 'CONFLICT')
                self.assertIn('CONDITION_CONFLICT', result['reasons'])
        result = self.lexicon.propose('iPhone 16 Б/У', 'PRODUCT', '/IPHONE (Б/У)')
        self.assertEqual(result['candidate_category'], 'IPHONE_USED')
        self.assertEqual(result['condition_source'], 'NAME')


if __name__ == '__main__':
    unittest.main()

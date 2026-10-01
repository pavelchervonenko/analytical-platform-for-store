#!/usr/bin/env python3
"""Offline, evidence-first catalog proposals. No database/network/write-back integration."""
import argparse
from collections import Counter
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re
import unicodedata

from build_review import write_xlsx
from probe_rules import read_xlsx
from catalog_registry import REGISTRY, REGISTRY_PATH, VERSION as REGISTRY_VERSION, CONFIRMED_CONDITIONS


FIELDS = {'family', 'item', 'compatibility', 'service', 'commercial', 'condition',
          'annotation', 'subtype', 'manufacturer', 'model_line'}
DEVICE_CATEGORIES = REGISTRY.proposal_categories('DEVICE')
SERVICE_CATEGORIES = REGISTRY.proposal_categories('SERVICE')
ITEM_CATEGORIES = REGISTRY.proposal_categories('ITEM')
FAMILY_BRANDS = {'IPHONE': 'APPLE', 'IPAD': 'APPLE', 'MACBOOK': 'APPLE', 'APPLE_WATCH': 'APPLE',
                 'APPLE_HEADPHONES': 'APPLE', 'SAMSUNG_PHONE': 'SAMSUNG',
                 'SAMSUNG_HEADPHONES': 'SAMSUNG', 'SAMSUNG_WATCH': 'SAMSUNG'}
CONDITION_FAMILIES = {'IPHONE', 'SAMSUNG_PHONE', 'IPAD', 'MACBOOK', 'OTHER_TABLET',
                      'OTHER_LAPTOP', 'APPLE_WATCH', 'SAMSUNG_WATCH', 'OTHER_WATCH'}

# Attribute constraints come from the same backend-owned registry as Java.
PHONE_CATEGORY_CONDITIONS = {code: entry.confirmed_conditions
                             for code, entry in REGISTRY.definitions.items()
                             if entry.confirmed_conditions}
FUNCTION_CATEGORIES = REGISTRY.function_categories()


class Lexicon:
    def __init__(self, data, connection_key=None):
        if data.get('format_version') != 1 or data.get('mode') != 'SHADOW_ONLY':
            raise ValueError('Only format 1 SHADOW_ONLY dictionaries are supported')
        self.data = data
        self.connection_key = connection_key
        self.rules = []
        ids = set()
        for rule in data['rules']:
            if (rule['id'] in ids or rule['field'] not in FIELDS or not rule.get('patterns')
                    or rule.get('input', 'name') not in {'name', 'source_group'}):
                raise ValueError('Invalid or duplicate rule: ' + rule['id'])
            ids.add(rule['id'])
            self.rules.append((rule, [re.compile(p) for p in rule['patterns']],
                               [re.compile(p) for p in rule.get('exclude', [])]))

    def normalize(self, name):
        name = unicodedata.normalize('NFKC', name).lower().replace('ё', 'е')
        name = re.sub(r'[-‐‑–—]', ' ', name)
        for alias in self.data['normalization_aliases']:
            name = re.sub(alias['pattern'], alias['replacement'], name)
        return ' '.join(name.split())

    def extract(self, name, input_field='name', family=None, source_kind=None):
        normalized = self.normalize(name)
        evidence = []
        for rule, patterns, excludes in self.rules:
            if rule.get('input', 'name') != input_field:
                continue
            if rule.get('required_family') and rule['required_family'] != family:
                continue
            if rule.get('required_source_kind') and rule['required_source_kind'] != source_kind:
                continue
            if rule.get('scope') and rule['scope'] != self.connection_key:
                continue
            if any(p.search(normalized) for p in excludes):
                continue
            matches = [m for p in patterns if (m := p.search(normalized))]
            if matches:
                evidence.append({'rule': rule['id'], 'field': rule['field'], 'value': rule['value'],
                                 'matched': matches[0].group(), 'span': list(matches[0].span()), 'input': input_field,
                                 'review': rule.get('review', ''), 'scope': rule.get('scope', '')})
        return normalized, evidence

    def propose(self, name, source_kind='PRODUCT', source_group=''):
        if source_kind not in {'PRODUCT', 'SERVICE'} or not name.strip():
            raise ValueError('Nonempty name and known source kind required')
        n, evidence = self.extract(name)
        def values(field):
            return {e['value'] for e in evidence if e['field'] == field}
        families, items, conditions = values('family'), values('item'), values('condition')
        # NEW + ASIS is compatible packaging/state information, unlike NEW + USED.
        if conditions == {'NEW', 'ASIS'}:
            conditions = {'ASIS'}
        condition_source = 'NAME' if conditions else 'UNKNOWN'
        reasons = set()
        category = ''
        role, types, targets = 'UNKNOWN', set(), set()
        inferred_maker = set()
        # A device's repair/activation annotation is not a sold service. Source kind wins.
        service_evidence = [e for e in evidence if e['field'] == 'service']
        primary_service = any(e['span'][0] == 0 for e in service_evidence)
        if (source_kind == 'SERVICE' or primary_service) and not values('commercial'):
            role, types = 'SERVICE', values('service')
            cats = {SERVICE_CATEGORIES[t] for t in types}
            if any(e['rule'] == 'bundle_repair_service' for e in evidence):
                cats = {'REPAIR_SERVICE'}
                reasons.add('BUNDLE_POLICY')
            if len(cats) == 1:
                category = next(iter(cats))
            elif cats:
                reasons.add('SERVICE_COMPOSITION_CONFLICT')
            else:
                reasons.add('SERVICE_TYPE_UNKNOWN')
        elif values('commercial'):
            role, types = 'COMMERCIAL', values('commercial')
            if len(types) == 1:
                category = next(iter(types))
            else:
                reasons.add('COMMERCIAL_CONFLICT')
        elif items:
            role = 'ACCESSORY'
            targets = families | values('compatibility')
            # A generic tablet mention is a target type, not proof of a non-Apple brand.
            if 'TABLET_GENERIC' in targets:
                targets.discard('OTHER_TABLET')
            if 'MIXED_BUNDLE' in items:
                items -= {'CASE', 'SCREEN_GLASS'}
            if items == {'CASE', 'KEYBOARD'}:
                items = {'KEYBOARD'}  # A keyboard case is one item, not two accessories.
                reasons.add('BUNDLE_POLICY')
            types = items
            if len(items) != 1:
                reasons.add('ITEM_TYPE_CONFLICT')
            else:
                item = next(iter(items))
                category = ITEM_CATEGORIES.get(item, '')
                if item in {'CASE', 'SCREEN_GLASS', 'CAMERA_GLASS', 'FILM', 'STRAP',
                            'STYLUS', 'STYLUS_TIPS', 'KEYBOARD', 'MOUSE'}:
                    if item in {'STYLUS', 'STYLUS_TIPS'} and re.search(r'\bapple pencil\b', n):
                        targets.add('IPAD')
                    if item == 'MOUSE' and re.search(r'\bmagic mouse\b', n):
                        targets.add('MACBOOK')
                    category, extra = accessory_category(item, targets)
                    reasons |= extra
                    # The approved leaf is AirPods accessories, not every Apple headphone.
                    if category == 'ACCESSORY_AIRPODS' and re.search(r'\bearpods\b', n):
                        category = ''
                        reasons.add('APPLE_HEADPHONE_TARGET_REVIEW')
            # Mentioned Apple/Samsung often describes compatibility, not the item's maker.
            inferred_maker = values('manufacturer') - {'APPLE', 'SAMSUNG'}
            if values('manufacturer') & {'APPLE', 'SAMSUNG'}:
                reasons.add('MAKER_VS_TARGET_CHECK')
        elif families:
            role, types = 'DEVICE', families
            if len(families) != 1:
                reasons.add('DEVICE_TYPE_CONFLICT')
            else:
                family = next(iter(families))
                # Approved source-group evidence applies only after identifying the sold device.
                group_evidence = self.extract(source_group, 'source_group', family, source_kind)[1]
                group_conditions = {e['value'] for e in group_evidence if e['field'] == 'condition'}
                if group_conditions and group_conditions != conditions:
                    evidence.extend(group_evidence)
                    if conditions:
                        condition_source = 'NAME_AND_APPROVED_GROUP_CONFLICT'
                        reasons.add('SOURCE_CONDITION_CONFLICT')
                    else:
                        condition_source = 'OWNER_APPROVED_SOURCE_GROUP'
                    conditions |= group_conditions
                inferred_maker = values('manufacturer') | ({FAMILY_BRANDS[family]} if family in FAMILY_BRANDS else set())
                if family in {'IPHONE', 'SAMSUNG_PHONE'}:
                    if len(conditions) == 1:
                        condition = next(iter(conditions))
                        if family == 'IPHONE':
                            category = 'IPHONE_USED' if condition == 'USED' else 'IPHONE_NEW_ASIS'
                        elif condition in {'USED', 'NEW'}:
                            category = 'SAMSUNG_' + condition
                        else:
                            reasons.add('CONDITION_UNSUPPORTED')
                else:
                    category = DEVICE_CATEGORIES[family]
                if family in CONDITION_FAMILIES and not conditions:
                    reasons.add('CONDITION_UNKNOWN')
                if family in {'OTHER_TABLET', 'OTHER_LAPTOP', 'OTHER_WATCH'}:
                    if not inferred_maker or inferred_maker & {'APPLE'}:
                        category = ''
                        reasons.add('BRAND_UNRESOLVED')
                if family == 'OTHER_HEADPHONES' and len(inferred_maker) == 1:
                    brand = next(iter(inferred_maker))
                    category = {'APPLE': 'HEADPHONES_APPLE', 'SAMSUNG': 'HEADPHONES_SAMSUNG'}.get(brand, 'HEADPHONES_OTHER')
                if family in {'OTHER_HEADPHONES', 'HAIR_STYLER'} and not inferred_maker:
                    category = ''
                    reasons.add('BRAND_UNRESOLVED')
        else:
            reasons.add('ITEM_TYPE_UNKNOWN')
        if 'DEVICE_ACCESSORY_BUNDLE' in values('annotation'):
            category = ''
            reasons.add('BUNDLE_COMPOSITION_CONFLICT')
        if len(conditions) > 1:
            reasons.add('CONDITION_CONFLICT')
            category = ''
        if len(inferred_maker) > 1:
            reasons.add('MANUFACTURER_AMBIGUOUS')
            if role == 'DEVICE':
                category = ''
        if role == 'DEVICE' and values('annotation') & {'REPAIR_OR_DEFECT', 'WARRANTY_NOTE', 'BUNDLE'}:
            reasons.add('DEVICE_ANNOTATION_REVIEW')
        if role == 'DEVICE' and re.search(r'\bband\b', n):
            reasons.add('INCLUDED_BAND_REVIEW')
        group = self.normalize(source_group)
        if role == 'DEVICE' and ((('iphone' in group) and 'IPHONE' not in families)
                                  or (('samsung' in group) and not families & {'SAMSUNG_PHONE', 'SAMSUNG_WATCH'})):
            reasons.add('SOURCE_GROUP_CONFLICT')
        if role == 'DEVICE' and re.search(r'б\s*/\s*у', group) and conditions == {'NEW'}:
            reasons.add('SOURCE_CONDITION_CONFLICT')
        relevant_fields = {'condition', 'annotation'}
        if role == 'DEVICE':
            relevant_fields |= {'family'}
        elif role == 'ACCESSORY':
            relevant_fields |= {'item', 'family', 'compatibility'}
        elif role == 'COMMERCIAL':
            relevant_fields |= {'commercial'}
        elif role == 'SERVICE':
            relevant_fields |= {'service'}
        reasons |= {e['review'] for e in evidence if e['review'] and e['field'] in relevant_fields
                    and not (e['field'] == 'compatibility' and e['value'] in families)
                    and not (e['review'] in {'GRADE_NOT_CONDITION', 'DISCOUNT_NOT_CONDITION'} and conditions)}
        if category:
            REGISTRY.require(category)
        else:
            reasons.add('NO_CATEGORY_PROPOSAL')
        return {'role': role, 'types': sorted(types), 'manufacturer_candidates': sorted(inferred_maker),
                'compatibility': sorted(targets), 'condition': next(iter(conditions)) if len(conditions) == 1 else '',
                'condition_source': condition_source,
                'candidate_category': category,
                'status': ('CONFLICT' if any('CONFLICT' in r for r in reasons) else
                           'NEEDS_REVIEW' if reasons else 'PROPOSAL'),
                'reasons': sorted(reasons), 'evidence': evidence,
                'normalized_name': n, 'effective_assignment': None}


def accessory_category(item, targets):
    mappings = {
        'CASE': {'IPHONE': 'CASE_APPLE_IPHONE', 'SAMSUNG_PHONE': 'CASE_SAMSUNG',
                 'IPAD': 'ACCESSORY_IPAD', 'MACBOOK': 'ACCESSORY_MAC',
                 'APPLE_HEADPHONES': 'ACCESSORY_AIRPODS', 'APPLE_WATCH': 'ACCESSORY_APPLE_WATCH',
                 'SAMSUNG_HEADPHONES': 'CASE_OTHER_DEVICE', 'LAPTOP_GENERIC': 'CASE_OTHER_DEVICE',
                 'TABLET_GENERIC': 'CASE_OTHER_DEVICE',
                 'OTHER_TABLET': 'CASE_OTHER_DEVICE', 'OTHER_LAPTOP': 'CASE_OTHER_DEVICE',
                 'SAMSUNG_WATCH': 'CASE_OTHER_DEVICE', 'OTHER_WATCH': 'CASE_OTHER_DEVICE',
                 'OTHER_HEADPHONES': 'CASE_OTHER_DEVICE', 'FITNESS': 'CASE_OTHER_DEVICE'},
        'SCREEN_GLASS': {'IPHONE': 'GLASS_IPHONE', 'SAMSUNG_PHONE': 'GLASS_SAMSUNG',
                         'IPAD': 'ACCESSORY_IPAD', 'MACBOOK': 'ACCESSORY_MAC',
                         'APPLE_WATCH': 'ACCESSORY_APPLE_WATCH'},
        'CAMERA_GLASS': {'IPHONE': 'GLASS_CAMERA_IPHONE', 'SAMSUNG_PHONE': 'GLASS_CAMERA_SAMSUNG'},
        'FILM': {'IPHONE': 'FILM_PHONE', 'SAMSUNG_PHONE': 'FILM_PHONE',
                 'IPAD': 'ACCESSORY_IPAD', 'OTHER_TABLET': 'OTHER_ACCESSORY_PRODUCT',
                 'TABLET_GENERIC': 'PROTECTIVE_FILM'},
        'STRAP': {'APPLE_WATCH': 'ACCESSORY_APPLE_WATCH'},
        'STYLUS': {'IPAD': 'ACCESSORY_IPAD'}, 'STYLUS_TIPS': {'IPAD': 'ACCESSORY_IPAD'},
        'KEYBOARD': {'IPAD': 'ACCESSORY_IPAD', 'MACBOOK': 'ACCESSORY_MAC'},
        'MOUSE': {'MACBOOK': 'ACCESSORY_MAC'},
    }
    cats = {mappings[item][t] for t in targets if t in mappings[item]}
    if len(cats) > 1:
        return '', {'TARGET_CONFLICT'}
    if len(cats) == 1:
        return next(iter(cats)), set()
    # Unknown target is explicitly reviewable, never silently inferred from the shop group.
    fallback = {'CASE': 'OTHER_CASE', 'SCREEN_GLASS': 'GLASS_PHONE_UNRESOLVED', 'FILM': 'FILM_PHONE'}
    return fallback.get(item, ''), {'TARGET_UNKNOWN'}



def validate_owner_attributes(decision):
    """Reject malformed/contradictory attributes before producing any output."""
    category = decision['category']
    REGISTRY.require(category)
    if 'confirmed_condition' in decision:
        condition = decision['confirmed_condition']
        if not isinstance(condition, str) or condition not in CONFIRMED_CONDITIONS:
            raise ValueError('Invalid confirmed condition')
        if category in PHONE_CATEGORY_CONDITIONS and condition not in PHONE_CATEGORY_CONDITIONS[category]:
            raise ValueError('Confirmed condition conflicts with phone category')
    if 'confirmed_function' in decision:
        function = decision['confirmed_function']
        if not isinstance(function, str) or function not in FUNCTION_CATEGORIES:
            raise ValueError('Invalid confirmed function')
        if category not in FUNCTION_CATEGORIES[function]:
            raise ValueError('Confirmed function conflicts with category')


def review_profile(row, decision):
    """A local review projection: confirmed values first, otherwise observed facts.

    Never infer condition/function from the category, suppress warnings or write an
    effective assignment. Multiple observed types remain multiple candidates.
    """
    condition = decision.get('confirmed_condition', '')
    function = decision.get('confirmed_function', '')
    targets = list(decision.get('confirmed_compatibility', []))
    return {
        'owner_confirmed_condition': condition,
        'owner_confirmed_function': function,
        'review_condition': condition or row['condition'],
        'review_condition_source': 'OWNER_CONFIRMED' if condition else row['condition_source'],
        'review_types': [function] if function else list(row['types']),
        'review_types_source': 'OWNER_CONFIRMED' if function else 'LEXICON' if row['types'] else 'UNKNOWN',
        'review_compatibility': targets if targets else list(row['compatibility']),
        'review_compatibility_source': ('OWNER_CONFIRMED' if targets else
                                        'LEXICON' if row['compatibility'] else 'UNKNOWN'),
    }


def apply_owner_decisions(records, decisions, connection_key):
    """Overlay exact, local owner decisions; never infer identity or change manufacturer facts."""
    if decisions.get('mode') != 'OWNER_CONFIRMED_NOT_APPLIED' or decisions.get('connection_key') != connection_key:
        raise ValueError('Owner decision mode/connection mismatch')
    if any('owner_decision' in r or 'owner_confirmed_category' in r for r in records):
        raise ValueError('Owner decisions require raw observations, not a previous review')
    known = {(r['source_kind'], r['code']): r for r in records}
    if len(known) != len(records):
        raise ValueError('Duplicate audit identity')
    matched = {}
    for decision in decisions['decisions']:
        key = decision['source_kind'], decision['code']
        if key in matched or key not in known:
            raise ValueError('Duplicate or missing owner decision identity')
        row = known[key]
        if row['name'] != decision['expected_name'] or row['source_group'] != decision['expected_group']:
            raise ValueError('Owner decision content changed: review again')
        if (not isinstance(decision['category'], str)
                or not re.fullmatch(r'[A-Z][A-Z0-9_]+', decision['category'])):
            raise ValueError('Invalid local category code')
        if not set(decision['resolved_reasons']) <= set(row['reasons']):
            raise ValueError('Owner decision reasons changed: review again')
        validate_owner_attributes(decision)
        compatibility = decision.get('confirmed_compatibility', [])
        allowed_targets = set(DEVICE_CATEGORIES) | {'IPHONE', 'SAMSUNG_PHONE', 'LAPTOP_GENERIC', 'TABLET_GENERIC', 'PHONE_UNIVERSAL'}
        if (not isinstance(compatibility, list)
                or any(not isinstance(value, str) or value not in allowed_targets for value in compatibility)
                or len(compatibility) != len(set(compatibility))):
            raise ValueError('Invalid confirmed compatibility')
        if decision['category'] == 'CASE_UNIVERSAL' and compatibility != ['PHONE_UNIVERSAL']:
            raise ValueError('Universal case requires explicit universal phone compatibility')
        if 'PHONE_UNIVERSAL' in compatibility and decision['category'] != 'CASE_UNIVERSAL':
            raise ValueError('Universal phone compatibility requires universal case category')
        matched[key] = decision
    result = []
    for original in records:
        row = deepcopy(original)
        decision = matched.get((row['source_kind'], row['code']))
        row['owner_confirmed_category'] = decision['category'] if decision else ''
        row['owner_confirmed_compatibility'] = list(decision.get('confirmed_compatibility', [])) if decision else []
        row.update(review_profile(original, decision or {}))
        if decision:
            row['lexical_proposal'] = deepcopy({key: original[key] for key in ['candidate_category', 'status', 'reasons']})
            row['owner_decision'] = deepcopy(decision)
            row['candidate_category'] = decision['category']
            row['reasons'] = sorted(set(row['reasons']) - set(decision['resolved_reasons']))
            row['status'] = ('CONFLICT' if any('CONFLICT' in reason for reason in row['reasons']) else
                             'NEEDS_REVIEW' if row['reasons'] else 'OWNER_CONFIRMED')
            row['legacy_difference'] = bool(row['legacy_category'] and row['candidate_category'] != row['legacy_category'])
        result.append(row)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--products', type=Path, required=True)
    parser.add_argument('--services', type=Path, required=True)
    parser.add_argument('--connection-key', required=True)
    parser.add_argument('--lexicon', type=Path, default=Path(__file__).with_name('catalog_lexicon.json'))
    parser.add_argument('--owner-decisions', type=Path, help='Exact local owner confirmations; never DB assignments')
    parser.add_argument('--baseline', type=Path, help='Optional verified local legacy replay, not DB assignments')
    parser.add_argument('--output', type=Path, required=True, help='New directory under ignored outputs/')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    if args.output.exists() or not args.output.resolve().is_relative_to(root / 'outputs'):
        raise ValueError('Use a new directory under ignored outputs/')
    dictionary = json.loads(args.lexicon.read_text())
    lexicon = Lexicon(dictionary, args.connection_key)
    hashes = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in [args.products, args.services]}
    baseline = {}
    if args.baseline:
        old = json.loads(args.baseline.read_text())
        if sorted(old['summary']['sources'].values()) != sorted(hashes.values()):
            raise ValueError('Baseline source fingerprints differ')
        baseline = {(r['source_kind'], r['code']): r for r in old['records']}
        if len(baseline) != len(old['records']):
            raise ValueError('Duplicate baseline identity')
    records = []
    identities = set()
    for path, kind in [(args.products, 'PRODUCT'), (args.services, 'SERVICE')]:
        for row in read_xlsx(path):
            identity = kind, row['Код']
            if not row['Код'] or identity in identities:
                raise ValueError('Missing or duplicate source identity')
            identities.add(identity)
            name = row['Наименование']
            group = row.get('Полная группа', '')
            prior = baseline.get(identity, {})
            if baseline and (not prior or prior['name'] != name or prior['source_group'] != group):
                raise ValueError('Baseline identity/content mismatch')
            r = {'source_kind': kind, 'code': row['Код'], 'name': name, 'source_group': group,
                 **lexicon.propose(name, kind, group), 'legacy_category': prior.get('auto_category', '')}
            r['legacy_difference'] = bool(prior and r['candidate_category'] and r['candidate_category'] != prior['auto_category'])
            records.append(r)
    if baseline and identities != baseline.keys():
        raise ValueError('Incomplete baseline comparison')
    decisions = (json.loads(args.owner_decisions.read_text()) if args.owner_decisions else
                 {'mode': 'OWNER_CONFIRMED_NOT_APPLIED', 'connection_key': args.connection_key, 'decisions': []})
    records = apply_owner_decisions(records, decisions, args.connection_key)
    rule_coverage = Counter(e['rule'] for r in records for e in r['evidence'])
    summary = {
        'scope': 'OFFLINE SHADOW proposals, NOT verified live assignments or financial accuracy',
        'policy_version': dictionary['policy_version'], 'connection_key': args.connection_key,
        'source_counts': dict(Counter(r['source_kind'] for r in records)),
        'rules': len(dictionary['rules']), 'rules_matched': len(rule_coverage),
        'coverage_by_source': {kind: {'rows': sum(r['source_kind'] == kind for r in records),
                                      'type_recognized': sum(r['source_kind'] == kind and bool(r['types']) for r in records),
                                      'category_proposed': sum(r['source_kind'] == kind and bool(r['candidate_category']) for r in records)}
                               for kind in ['PRODUCT', 'SERVICE']}, 'type_recognized': sum(bool(r['types']) for r in records),
        'category_proposed': sum(bool(r['candidate_category']) for r in records),
        'statuses': dict(Counter(r['status'] for r in records)),
        'categories': dict(Counter(r['candidate_category'] for r in records)),
        'review_reasons': dict(Counter(x for r in records for x in r['reasons'])),
        'legacy_differences': sum(r['legacy_difference'] for r in records),
        'source_sha256': hashes, 'code_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                              for p in [Path(__file__), args.lexicon,
                                                        Path(__file__).with_name('catalog_registry.py'), REGISTRY_PATH]},
        'category_registry_version': REGISTRY_VERSION,
        'category_registry_sha256': REGISTRY.sha256,
        'assignments_written': 0, 'live_database_verified': False,
        'review_profile_version': 1,
        'owner_confirmed_conditions': sum(bool(r['owner_confirmed_condition']) for r in records),
        'owner_confirmed_functions': sum(bool(r['owner_confirmed_function']) for r in records),
        'owner_confirmed_categories': sum(bool(r['owner_confirmed_category']) for r in records),
        'owner_decisions_sha256': hashlib.sha256(args.owner_decisions.read_bytes()).hexdigest() if args.owner_decisions else None,
        'baseline_sha256': hashlib.sha256(args.baseline.read_bytes()).hexdigest() if args.baseline else None,
    }
    columns = ['source_kind', 'code', 'name', 'source_group', 'role', 'types', 'candidate_category',
               'condition', 'condition_source', 'manufacturer_candidates', 'compatibility', 'status', 'reasons', 'legacy_category', 'owner_confirmed_category', 'owner_confirmed_compatibility',
               'owner_confirmed_condition', 'owner_confirmed_function', 'review_condition', 'review_condition_source',
               'review_types', 'review_types_source', 'review_compatibility', 'review_compatibility_source']
    headers = ['Источник', 'Код', 'Наименование', 'Группа LiveSklad', 'Роль', 'Тип', 'Категория — предложение',
               'Состояние — исходные признаки', 'Источник исходного состояния', 'Производитель — кандидаты', 'Совместимость — признаки', 'Статус',
               'Причины проверки', 'Старое автоправило — НЕ база', 'Подтверждено владельцем — НЕ применено в БД', 'Совместимость — подтверждено владельцем',
               'Состояние — подтверждено владельцем', 'Функция — подтверждено владельцем',
               'Состояние — итог проверки, НЕ БД', 'Источник итогового состояния',
               'Типы и функция — итог проверки, НЕ БД', 'Источник итоговых типов',
               'Совместимость — итог проверки, НЕ БД', 'Источник итоговой совместимости']
    def table(rows):
        return [headers + ['Правила', 'Совпадения']] + [[
            ', '.join(r[c]) if isinstance(r[c], list) else r[c] for c in columns
        ] + [', '.join(e['rule'] for e in r['evidence']),
             '; '.join(e['rule'] + ': ' + e['matched'] for e in r['evidence'])] for r in rows]
    coverage = rule_coverage
    dictionary_rows = [['Правило', 'Признак', 'Значение', 'Пример', 'Совпало строк', 'Область', 'Проверка', 'Шаблоны', 'Исключения', 'Источник признака']]
    dictionary_rows += [[r['id'], r['field'], r['value'], r['example'], coverage[r['id']],
                         r.get('scope', 'ANY'), r.get('review', ''), ' | '.join(r['patterns']),
                         ' | '.join(r.get('exclude', [])), r.get('input', 'name')] for r in dictionary['rules']]
    sheets = [('Инструкция', [['Назначение', 'Предложения по выгрузке, не изменения базы. Подтверждения владельца показаны отдельно; в БД не применены.'],
                             ['Итоговый профиль', 'Подтверждённые признаки имеют приоритет; исходные признаки и подтверждения сохранены отдельно. Это итог локального просмотра, НЕ импортный файл и НЕ назначение в БД.'],
                             ['Источники профиля', 'OWNER_CONFIRMED — точное решение владельца; NAME — название; OWNER_APPROVED_SOURCE_GROUP — согласованное правило группы; LEXICON — признаки словаря; UNKNOWN — данных нет.'],
                             ['Неполные данные', 'Неизвестное состояние не становится NEW. Категория сама по себе не подтверждает состояние или функцию; несколько типов не сворачиваются в один. Предупреждения и конфликты сохраняются.'],
                             ['Статус PROPOSAL', 'Нет обнаруженных противоречий; НЕ означает подтверждение руководителем.'],
                             ['Начать', 'Листы Без категории и Конфликты, затем Проверить и Все товары.'],
                             ['Производитель', 'Отделен от совместимости; пустое поле не означает OTHER.'],
                             ['Зарплата и показатели', 'Не менялись. Старое автоправило не равно фактической категории в базе.'],
                             ['Покрытие типом', f"{summary['type_recognized']} / {len(records)}"],
                             ['Покрытие предложением', f"{summary['category_proposed']} / {len(records)}"]]),
              ('Словарь', dictionary_rows), ('Все товары', table([r for r in records if r['source_kind'] == 'PRODUCT'])),
              ('Работы', table([r for r in records if r['source_kind'] == 'SERVICE'])),
              ('Без категории', table([r for r in records if not r['candidate_category']])),
              ('Конфликты', table([r for r in records if r['status'] == 'CONFLICT'])),
              ('Проверить', table([r for r in records if r['status'] not in {'PROPOSAL', 'OWNER_CONFIRMED'}])),
              ('Изменения против автоправил', table([r for r in records if r['legacy_difference']]))]
    reason_text = {
        'MODEL_ALIAS': 'Модель распознана по сокращению или линейке: проверить устройство назначения.',
        'TARGET_UNKNOWN': 'Не указано совместимое устройство. Не угадывать по группе LiveSklad.',
        'TARGET_MODEL_REVIEW': 'Совместимость предположена по Watch/размерам ремешка.',
        'CONDITION_UNKNOWN': 'Нет явного нового/б/у/ASIS. Группа и состояние корпуса не доказательство.',
        'GRADE_NOT_CONDITION': 'Оценка корпуса A/B/C не означает б/у.',
        'DISCOUNT_NOT_CONDITION': 'Уценка не означает б/у.',
        'MAKER_VS_TARGET_CHECK': 'Apple/Samsung может означать совместимость, а не производителя аксессуара.',
        'SOURCE_GROUP_CONFLICT': 'Распознанное устройство расходится с исходной группой LiveSklad.',
        'SOURCE_CONDITION_CONFLICT': 'Явное состояние расходится с исходной группой.',
        'DEVICE_ANNOTATION_REVIEW': 'Есть пометка ремонта/дефекта/гарантии/комплекта. Это не отдельная услуга.',
        'INCLUDED_BAND_REVIEW': 'Похоже на устройство с комплектным ремешком, не самостоятельный ремешок.',
        'NO_CATEGORY_PROPOSAL': 'Признаков недостаточно для предложения категории.',
        'ACCESSORY_FUNCTION_UNKNOWN': 'Назначение аксессуара нужно уточнить.',
        'STORE_SPECIFIC_RULE': 'Использовано правило именно этого каталога/подключения.',
        'BRAND_UNRESOLVED': 'Бренд не установлен: OTHER автоматически не назначается.',
        'BUNDLE_POLICY': 'Составной товар/работа: предложен учет одной строкой, без удвоения.',
        'APPLE_HEADPHONE_TARGET_REVIEW': 'Аксессуар EarPods нельзя автоматически считать аксессуаром AirPods.',
        'ITEM_TYPE_UNKNOWN': 'Тип не распознан.',
        'SERVICE_TYPE_UNKNOWN': 'Известно, что это работа, но неясно какая.',
    }
    sheets.append(('Причины проверки', [['Код', 'Пояснение', 'Строк']] +
                   [[key, reason_text.get(key, 'Противоречие признаков: нужна ручная проверка.'), count]
                    for key, count in sorted(summary['review_reasons'].items())]))
    for r in records:
        observation = {'connection_key': args.connection_key, 'source_kind': r['source_kind'],
                       'code': r['code'], 'name': r['name'], 'source_group': r['source_group'],
                       'policy_version': dictionary['policy_version'], 'code_sha256': summary['code_sha256'],
                       'owner_decisions_sha256': summary['owner_decisions_sha256']}
        r['observation_sha256'] = hashlib.sha256(
            json.dumps(observation, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    args.output.mkdir(parents=True)
    (args.output / 'lexicon-proposals.json').write_text(json.dumps({'summary': summary, 'records': records}, ensure_ascii=False, indent=2), encoding='utf-8')
    write_xlsx(args.output / 'catalog-lexicon-review.xlsx', sheets)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Offline payroll proposal workbook. No DB/network access or import payloads.

Uses a pinned catalog replay and a prior SQL fallback probe as separate evidence.
Neither is an effective production payroll assignment. Customer rows stay in outputs/.
"""
from __future__ import annotations

import argparse
from collections import Counter
import csv
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/catalog-audit'))
from build_review import csv_cell, write_xlsx
from probe_rules import read_xlsx

VERSION = 'payroll-catalog-proposal-2026-09-30-v3'
PAID_ROLES = ('TECH_TIER_1', 'TECH_TIER_2', 'ACCESSORY', 'SERVICE',
              'PLAYSTATION_SUBSCRIPTION', 'PAID_REPAIR')
LABELS = dict(zip(PAID_ROLES, ('Техника первой категории', 'Техника второй категории',
                            'Аксессуары', 'Обычные платные услуги',
                            'Подписки PlayStation', 'Платные ремонты')))
LABELS['UNMAPPED'] = 'Требуется решение'
LABELS['EXCLUDE'] = 'Без прямого зарплатного начисления — не седьмая расчётная категория'
BASES = {'TECH_TIER_1': 'Количество единиц', 'TECH_TIER_2': 'Количество единиц',
         'ACCESSORY': 'Чистый оборот', 'SERVICE': 'Чистый оборот',
         'PLAYSTATION_SUBSCRIPTION': 'Валовая прибыль: продажа минус себестоимость подписки',
         'PAID_REPAIR': 'Валовая прибыль: оплата клиента минус запчасти и подрядчик',
         'UNMAPPED': 'Не определена — не считать нулевой выплатой',
         'EXCLUDE': 'Без прямого начисления по подтверждённому решению; KPI отдельно'}
ACCESSORIES = {'CASE', 'SCREEN_GLASS', 'CAMERA_GLASS', 'FILM', 'STRAP', 'CHARGER',
               'POWER_BANK', 'CARDHOLDER', 'CONNECTIVITY', 'STYLUS', 'STYLUS_TIPS',
               'MOUSE', 'KEYBOARD', 'HOLDER', 'TRACKER', 'GAMING_ACCESSORY'}
OTHER_DEVICES = {'IPAD', 'OTHER_TABLET', 'OTHER_LAPTOP', 'APPLE_WATCH', 'SAMSUNG_WATCH',
                 'OTHER_WATCH', 'APPLE_HEADPHONES', 'SAMSUNG_HEADPHONES', 'OTHER_HEADPHONES',
                 'SPEAKER', 'MICROPHONE', 'FITNESS', 'CAMERA', 'SMART_GLASSES'}
PS5 = re.compile(r'\b(?:play\s*station\s*5|ps\s*5)\b', re.I)
ACCESSORY_WORD = re.compile(r'чехол|кабел|dualsense|dual\s+sense|дисковод|disc\s*drive|charging\s*station', re.I)
QUESTIONS = {
    'WARRANTY_TERMS': ('Гарантии и Care-продукты',
        'Это собственная платная услуга магазина? Подтвердить SERVICE от чистого оборота либо иной состав позиции.',
        'SERVICE — если собственная платная услуга; иначе уточнить состав'),
    'PACKAGING_POLICY': ('Пакеты и упаковка',
        'Начислять как аксессуар или исключить только из вознаграждения? Влияние выручки на KPI решается отдельно.',
        'ACCESSORY / EXCLUDE по явному решению'),
    'TEST_CARD_POLICY': ('Тестовая карточка',
        'Исключение из аналитики согласовано. Подтвердить отдельно отсутствие зарплатного начисления и проверить реальные продажи.',
        'EXCLUDE — предлагаем для тестовой карточки, пока не применено'),
    'SOFTWARE_LICENSE': ('Программное обеспечение без вида услуги',
        'Это установка программы или продажа лицензии? По одному названию базу начисления не определить.',
        'SERVICE — для установки; для лицензии нужно правило'),
    'FIRMWARE_SCOPE': ('Прошивка и перепрошивка',
        'Обычная настройка ПО или платный ремонт неисправности? Если ремонт — нужна полная себестоимость.',
        'SERVICE / PAID_REPAIR'),
    'GLASS_WORK_SCOPE': ('Замена стекла без уточнения',
        'Замена детали устройства или наклейка защитного стекла? Единую строку нельзя разбить на товар и работу без состава.',
        'PAID_REPAIR / SERVICE; составная продажа требует состава'),
    'DIAGNOSTICS_SCOPE': ('Диагностика',
        'Самостоятельная платная диагностика или часть ремонта? Бесплатность не означает автоматический EXCLUDE.',
        'SERVICE — самостоятельная платная услуга; иначе уточнить'),
    'CLEANING_DISASSEMBLY': ('Чистка с разборкой',
        'Обычная платная чистка или ремонтная работа с запчастями/подрядчиком?',
        'SERVICE / PAID_REPAIR'),
    'TYPE_CONFLICT': ('Недостаточно сведений о самом товаре',
        'Нужно подтвердить вид продаваемой позиции; упоминание устройства не определяет зарплатный уровень.',
        'Требуется разбор'),
}


def normalize(name):
    return ' '.join(name.casefold().replace('ё', 'е').split())


def proposal(record):
    """A recommendation, never an approval; analytics labels do not select payroll."""
    name = normalize(record['name'])
    types = set(record.get('review_types', []))
    role = record.get('role')

    def result(category, reason, question=''):
        return dict(proposed_payroll=category, basis=BASES[category], reason=reason,
                    question_group=question,
                    question=QUESTIONS[question][1] if question else '',
                    alternatives=QUESTIONS[question][2] if question else '',
                    proposal_status='NEEDS_DECISION' if question else 'RULE_SUPPORTED_PROPOSAL',
                    cost_check=('Нужна полная себестоимость каждой продажи; в этом отчёте не проверена'
                                if category in {'PAID_REPAIR', 'PLAYSTATION_SUBSCRIPTION'}
                                else 'Не определяет базу этой роли' if category != 'UNMAPPED'
                                else 'Зависит от решения'))

    def unresolved(question):
        return result('UNMAPPED', 'Недостаточно подтверждения зарплатной роли; старый fallback не является решением.', question)

    if types & {'WARRANTY_GENERIC', 'PREMIUM_PROTECTION'}:
        return unresolved('WARRANTY_TERMS')
    if 'PACKAGING' in types:
        return unresolved('PACKAGING_POLICY')
    if 'TEST_CARD' in types:
        return unresolved('TEST_CARD_POLICY')

    if role == 'SERVICE':
        if re.fullmatch(r'microsoft\s+office', name):
            return unresolved('SOFTWARE_LICENSE')
        if re.search(r'\b(?:пере)?прошивк', name):
            return unresolved('FIRMWARE_SCOPE')
        if (re.search(r'замена\s+стекла', name)
                and not re.search(r'диспле|задн', name)):
            return unresolved('GLASS_WORK_SCOPE')
        if 'DIAGNOSTICS' in types:
            return unresolved('DIAGNOSTICS_SCOPE')
        if 'CLEANING' in types and 'разборк' in name:
            return unresolved('CLEANING_DISASSEMBLY')
        if 'REPAIR' in types and types <= {'REPAIR', 'CLEANING'}:
            return result('PAID_REPAIR', 'R-09: ремонтная работа; полная себестоимость проверяется отдельно. Не дублировать в SERVICE.')
        if types and types <= {'SETUP', 'CLEANING'}:
            return result('SERVICE', 'R-09: обычная платная услуга/настройка/чистка, база — чистый оборот.')
        return unresolved('TYPE_CONFLICT')

    # Type of the sold item comes before a device mentioned as compatibility.
    if role == 'ACCESSORY':
        if types and types <= ACCESSORIES:
            return result('ACCESSORY', 'R-10: сопутствующий товар. Марка совместимого устройства и неизвестная совместимость не меняют зарплатную базу.')
        if types == {'MIXED_BUNDLE'} and re.search(r'чехол\s*\+\s*стекло', name):
            return result('ACCESSORY', 'Чехол + стекло: обе части аксессуары; начисление один раз с оборота единой позиции, без искусственного разбиения.')
        return unresolved('TYPE_CONFLICT')

    if role == 'DEVICE' and len(types) == 1:
        kind = next(iter(types))
        if ACCESSORY_WORD.search(name):
            return unresolved('TYPE_CONFLICT')
        if kind in {'IPHONE', 'SAMSUNG_PHONE', 'OTHER_PHONE', 'MACBOOK'}:
            return result('TECH_TIER_1', 'R-10: телефон/смартфон либо MacBook. Состояние и память не меняют зарплатный уровень.')
        if kind == 'HAIR_STYLER':
            if re.search(r'\bdyson\b', name):
                return result('TECH_TIER_1', 'R-10: самостоятельная техника Dyson, не аксессуар для Dyson.')
            return result('TECH_TIER_2', 'R-10: прочая самостоятельная техника; первый уровень не распространяется на все стайлеры.')
        if kind == 'CONSOLE':
            if PS5.search(name):
                return result('TECH_TIER_1', 'R-10: именно консоль PlayStation 5; не переносить правило на все консоли и аксессуары.')
            return result('TECH_TIER_2', 'R-10: другая самостоятельная игровая консоль, не PS5.')
        if kind in OTHER_DEVICES:
            return result('TECH_TIER_2', 'R-10: самостоятельная техника, не перечисленная в первом уровне; iPad не является MacBook.')
    return unresolved('TYPE_CONFLICT')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def index_rows(rows):
    indexed = {}
    for row in rows:
        key = row['source_kind'], str(row['code'])
        if key[0] not in {'PRODUCT', 'SERVICE'} or not key[1] or key in indexed:
            raise ValueError('Missing/duplicate export identity')
        indexed[key] = row
    return indexed


def build_records(lexicon, automatic, fallback):
    candidates, old, sql = [index_rows(rows) for rows in (lexicon['records'], automatic['records'], fallback['rows'])]
    if candidates.keys() != old.keys() or candidates.keys() != sql.keys():
        raise ValueError('Input populations differ')
    result = []
    for key, record in candidates.items():
        previous, probed = old[key], sql[key]
        if (record['name'] != previous['name'] or record['name'] != probed['name']
                or record['source_group'] != previous['source_group']
                or probed['before'] != previous['auto_category']
                or probed['after'] != record['candidate_category']):
            raise ValueError('Observation mismatch')
        row = {k: record[k] for k in ('source_kind', 'code', 'name', 'source_group')}
        row.update(proposal(record))
        analytical = record['candidate_category']
        row.update(
            analytical_candidate=analytical,
            analytical_display='SETUP_SERVICE' if analytical in {'REPAIR_SERVICE', 'DIAGNOSTICS_SERVICE'} else analytical,
            analytical_status=('Детализация сервиса отложена; сохраняем общий Сервис'
                               if analytical in {'REPAIR_SERVICE', 'DIAGNOSTICS_SERVICE'} else record['status']),
            observed_types=' | '.join(record.get('review_types', [])),
            type_evidence=record.get('review_types_source', 'UNKNOWN'),
            source_review_reasons=' | '.join(record.get('reasons', [])),
            current_db_payroll='НЕ ПРОВЕРЕНО: нет актуального назначения и интервала из БД',
            old_auto_category=previous['auto_category'],
            old_fallback=probed['before_payroll'],
            candidate_fallback=probed['after_payroll'] if probed['after_exists'] else 'КАТЕГОРИЯ ОТЛОЖЕНА — SQL НЕ СРАВНИВАЛСЯ',
            differs_from_old_fallback=(row['proposed_payroll'] != 'UNMAPPED' and row['proposed_payroll'] != probed['before_payroll']),
            differs_from_candidate_fallback=(row['proposed_payroll'] != 'UNMAPPED' and probed['after_exists'] and row['proposed_payroll'] != probed['after_payroll']),
            effective_from='', reviewer_answer='', approved=False,
            owner_confirmed_payroll=False, owner_decision_id='',
            identity_status='Код выгрузки + тип; требуется connection + provider ID / products.id перед применением',
        )
        result.append(row)
    return result



def apply_confirmations(records, decisions, input_hashes):
    """Apply a bounded owner decision to the review only, never to the database."""
    if (decisions.get('format_version') != 1 or decisions.get('scope') != 'REVIEW_ONLY'
            or decisions.get('input_sha256') != input_hashes
            or decisions.get('effective_from') is not None):
        raise ValueError('Unbound confirmation or effective date not authorized')
    indexed = index_rows(records)
    seen = set()
    validated = []
    for entry in decisions['confirmations']:
        key = entry['source_kind'], entry['code']
        if key in seen or key not in indexed:
            raise ValueError('Duplicate or missing confirmation target')
        seen.add(key)
        r = indexed[key]
        if (not entry.get('decision_id') or entry['payroll_category'] not in (*PAID_ROLES, 'EXCLUDE')
                or (entry['payroll_category'] == 'EXCLUDE'
                    and entry.get('no_direct_reward_confirmed') is not True)
                or r['proposal_status'] != 'NEEDS_DECISION'
                or not entry.get('expected_question_group')
                or r['question_group'] != entry['expected_question_group']):
            raise ValueError('Confirmation no longer matches the reviewed question')
        validated.append((r, entry))
    # Validate every target before changing any review row.
    for r, entry in validated:
        category = entry['payroll_category']
        r.update(resolved_question_group=r['question_group'], resolved_question=r['question'],
                 proposed_payroll=category, basis=BASES[category],
                 proposal_status='OWNER_CONFIRMED', owner_confirmed_payroll=True,
                 owner_decision_id=entry['decision_id'], reviewer_answer=entry.get('source_reply', decisions['source_reply']),
                 reason=entry['reason'], question_group='', question='', alternatives='',
                 cost_check=('Нужна полная себестоимость каждой продажи; в этом отчёте не проверена'
                             if category in {'PAID_REPAIR', 'PLAYSTATION_SUBSCRIPTION'}
                             else 'Прямое начисление исключено; влияние на KPI проверяется отдельно'
                             if category == 'EXCLUDE' else 'Не определяет базу этой роли'),
                 differs_from_old_fallback=category != r['old_fallback'],
                 differs_from_candidate_fallback=(r['candidate_fallback'] in {*PAID_ROLES, 'EXCLUDE', 'UNMAPPED'}
                                                  and category != r['candidate_fallback']))
    return records


COLUMNS = [('source_kind', 'Товар / работа'), ('code', 'Код LiveSklad'), ('name', 'Название'),
           ('source_group', 'Группа LiveSklad'), ('analytical_display', 'Аналитика — локальный разбор, НЕ БД'),
           ('proposed_payroll', 'Предлагаемая зарплатная категория'), ('proposal_status', 'Статус предложения'),
           ('basis', 'База начисления'), ('reason', 'Основание'),
           ('question', 'Вопрос руководителю'), ('alternatives', 'Варианты для решения'),
           ('current_db_payroll', 'Текущая категория БД'), ('old_fallback', 'Авторасчёт ДО — НЕ назначение БД'),
           ('candidate_fallback', 'Авторасчёт с новой аналитикой — НЕ назначение БД'),
           ('cost_check', 'Проверка себестоимости'), ('effective_from', 'Дата начала — не согласована'),
           ('reviewer_answer', 'Ответ руководителя'), ('observed_types', 'Признаки самого товара'),
           ('type_evidence', 'Источник признаков'), ('source_review_reasons', 'Замечания аналитического разбора'),
           ('analytical_candidate', 'Кандидат детализации, в том числе отложенной'),
           ('analytical_status', 'Статус аналитического кандидата'),
           ('owner_confirmed_payroll', 'Роль подтверждена владельцем — НЕ применена в БД'),
           ('owner_decision_id', 'Решение владельца')]


def tabular(records):
    return [[label for _, label in COLUMNS]] + [[r.get(k, '') for k, _ in COLUMNS] for r in records]


def validate_sources(lexicon, automatic):
    sources = lexicon['summary']['source_sha256']
    if sources != automatic['summary']['sources']:
        raise ValueError('Different raw exports')
    expected = index_rows(lexicon['records'])
    kinds = set()
    for filename, digest in sources.items():
        if sha(filename) != digest:
            raise ValueError('Source export hash changed')
        raw = read_xlsx(filename)
        # Do not infer identity across files using product name.
        matching_kinds = [kind for kind in ('PRODUCT', 'SERVICE')
                          if {(kind, r['Код']) for r in raw} == {k for k in expected if k[0] == kind}]
        if len(matching_kinds) != 1 or matching_kinds[0] in kinds:
            raise ValueError('Ambiguous source identity')
        kind = matching_kinds[0]
        kinds.add(kind)
        if len({r['Код'] for r in raw}) != len(raw):
            raise ValueError('Duplicate raw export code')
        for r in raw:
            if r['Наименование'] != expected[kind, r['Код']]['name']:
                raise ValueError('Source name differs')
    if kinds != {'PRODUCT', 'SERVICE'}:
        raise ValueError('Both source kinds required')
    return sources


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--lexicon', type=Path, required=True)
    p.add_argument('--automatic', type=Path, required=True)
    p.add_argument('--fallback', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--confirmations', type=Path, help='Bounded owner decisions for this exact input set')
    args = p.parse_args()
    os.umask(0o077)
    inputs = [args.lexicon, args.automatic, args.fallback]
    hashes = {str(path.resolve()): sha(path) for path in inputs}
    lexicon, automatic, fallback = [json.loads(path.read_text(encoding='utf-8')) for path in inputs]
    raw_hashes = validate_sources(lexicon, automatic)
    for path, digest in fallback['summary']['input_sha256'].items():
        if sha(ROOT / path) != digest:
            raise ValueError('Prior SQL probe input has changed')
    records = build_records(lexicon, automatic, fallback)
    if args.confirmations:
        confirmation_hash = sha(args.confirmations)
        decisions = json.loads(args.confirmations.read_text(encoding='utf-8'))
        apply_confirmations(records, decisions, hashes)
        hashes[str(args.confirmations.resolve())] = confirmation_hash
    counts = Counter(r['proposed_payroll'] for r in records)
    source_counts = dict(Counter(r['source_kind'] for r in records))
    if source_counts != lexicon['summary']['source_counts']:
        raise ValueError('Source counts do not reconcile')
    disputed = [r for r in records if r['proposal_status'] == 'NEEDS_DECISION']
    changed = [r for r in records if r['differs_from_old_fallback'] or r['differs_from_candidate_fallback']]
    summary = dict(version=VERSION, created_at=datetime.now(timezone.utc).isoformat(),
                   scope='Pinned LiveSklad exports + offline proposals; NOT current production assignments',
                   source_counts=source_counts, total=len(records), payroll_counts=dict(counts),
                   disputed_groups=dict(Counter(r['question_group'] for r in disputed)),
                   proposed_rows=len(records)-len(disputed), disputed_rows=len(disputed),
                   owner_confirmed_rows=sum(r['owner_confirmed_payroll'] for r in records),
                   rule_supported_rows=sum(r['proposal_status']=='RULE_SUPPORTED_PROPOSAL' for r in records),
                   excluded_rows=sum(r['proposed_payroll']=='EXCLUDE' for r in records),
                   differs_from_old_fallback=sum(r['differs_from_old_fallback'] for r in records),
                   differs_from_candidate_fallback=sum(r['differs_from_candidate_fallback'] for r in records),
                   input_sha256=hashes, raw_source_sha256=raw_hashes, script_sha256=sha(__file__),
                   database_verified=False, effective_dates_selected=False, assignments_written=0,
                   payroll_calculations_changed=0, auto_import_allowed=False)
    matrix = Counter((r['analytical_display'], r['proposed_payroll']) for r in records)
    instructions = [['Раздел', 'Инструкция'],
        ['Начать', 'Сводка → Спорные позиции → Расхождения авторасчёта. Все позиции доступны по фильтру или на листе своей зарплатной категории.'],
        ['Статусы', 'RULE_SUPPORTED_PROPOSAL: предложение следует инструкции. OWNER_CONFIRMED: роль согласована владельцем, но не применена в БД. NEEDS_DECISION: зарплатная роль пока UNMAPPED. approved=false означает отсутствие разрешения автоматического импорта, а не отмену согласованной роли.'],
        ['Актуальность', 'Использована существующая выгрузка 3317 товаров + 38 работ, хеши исходных XLSX проверены. Новые товары после выгрузки сюда не входят. Дата отчёта не является датой выгрузки.'],
        ['Текущая БД', 'Фактические датированные зарплатные назначения не проверены. Старый и новый fallback взяты из локального SQL-аудита, не из production.'],
        ['Дата', 'Дата начала намеренно пуста. Ни прошлый месяц, ни первое число текущего месяца не выбраны автоматически.'],
        ['Шесть ролей', 'Ставки зависят от результатов магазина и сотрудника. Здесь определяется вид базы, а не персональная выплата.'],
        ['Спорные', 'Укажите код + Товар/Работа + выбранную роль либо ответ на вопрос. Не нужно повторно присылать весь каталог.'],
        ['UNMAPPED', 'Неполный расчёт, а не нулевая выплата. EXCLUDE — техническое отсутствие прямого начисления по отдельному подтверждённому решению, не седьмая оплачиваемая категория. Аналитическая категория и KPI автоматически не меняются.'],
        ['Сервис', 'Аналитическая детализация ремонта/диагностики отложена; зарплатная PAID_REPAIR не требует аналитического разделения.'],
        ['Себестоимость', 'Категория ремонта сохраняется даже без стоимости. Себестоимость каждой продажи должна включать запчасти и подрядчика, без двойного учёта. Здесь стоимости не проверялись.'],
        ['Границы', 'Нет подключения к БД, изменения назначений или пересчёта зарплат. Таблица не предназначена для автоматического импорта.'],
        ['Идентичность', 'Код + тип идентифицирует строку только в этой выгрузке. До применения проверить connection/provider ID, overrides, интервалы и влияние на продажи.'],
        ['Конфиденциальность', 'Названия из каталога могут содержать серийные номера. Файлы локальные, исключены из Git.']]
    totals = [['Категория', 'Описание', 'Товаров', 'Работ', 'Всего', 'База']]
    for role in (*PAID_ROLES, 'EXCLUDE', 'UNMAPPED'):
        totals.append([role, LABELS[role], sum(r['source_kind']=='PRODUCT' and r['proposed_payroll']==role for r in records),
                       sum(r['source_kind']=='SERVICE' and r['proposed_payroll']==role for r in records), counts[role], BASES[role]])
    totals.append(['ИТОГО', '', source_counts['PRODUCT'], source_counts['SERVICE'], len(records), 'Одна строка — одна предлагаемая роль'])
    question_rows = [['Группа', 'Количество', 'Вопрос', 'Варианты']]
    for code, count in sorted(summary['disputed_groups'].items()):
        label, question, options = QUESTIONS[code]
        question_rows.append([label, count, question, options])
    sheets = [('Инструкция', instructions), ('Сводка', totals), ('Вопросы по группам', question_rows),
              ('Спорные позиции', tabular(disputed)), ('Расхождения авторасчёта', tabular(changed)),
              ('Аналитика и зарплата', [['Аналитика локального разбора', 'Предлагаемая зарплата', 'Строк']]
               + [[a, b, count] for (a, b), count in sorted(matrix.items())]),
              ('Все позиции', tabular(records))]
    for role in (*PAID_ROLES, 'EXCLUDE'):
        sheets.append((role, tabular([r for r in records if r['proposed_payroll']==role])))
    # Verify inputs again before publishing, and never overwrite an earlier review.
    if any(sha(path) != digest for path, digest in {**hashes, **raw_hashes}.items()):
        raise ValueError('Inputs changed during review')
    args.output.mkdir(parents=True, exist_ok=False)
    write_xlsx(args.output/'payroll-catalog-mapping.xlsx', sheets)
    with (args.output/'payroll-catalog-mapping.csv').open('x', encoding='utf-8-sig', newline='') as stream:
        csv.writer(stream, delimiter=';').writerows([[csv_cell(v) for v in row] for row in tabular(records)])
    for filename, data in [('payroll-catalog-mapping.json', dict(summary=summary, records=records)), ('summary.json', summary)]:
        with (args.output/filename).open('x', encoding='utf-8') as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
    print(json.dumps({k: v for k, v in summary.items() if k not in {'input_sha256','raw_source_sha256'}}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()

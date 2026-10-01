#!/usr/bin/env python3
"""Build a local, non-importable review workbook from an actual rule-engine audit.

No database access or assignment writes. Customer-specific proposals are a separate
local JSON input, not executable classifier rules. Standard library only.
"""
from __future__ import annotations

import argparse
from collections import Counter
import csv
import hashlib
import json
from pathlib import Path
import re
from xml.sax.saxutils import escape, quoteattr
import zipfile

NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
COLUMNS = [
    ('code', 'Код LiveSklad'), ('name', 'Наименование'),
    ('source_group', 'Группа LiveSklad'), ('source_kind', 'Товар / работа'),
    ('auto_category', 'Результат автоправила — НЕ категория БД'),
    ('auto_condition', 'Состояние по автоправилу'), ('auto_rule', 'Правило'),
    ('suggested_category', 'Предложение в существующих категориях'),
    ('proposed_detail', 'Предложение подробной категории / подтипа'),
    ('priority', 'Приоритет проверки'), ('reason', 'Обоснование / ограничения'),
    ('initial_approved_category', 'Старый импорт — НЕ текущее назначение'),
    ('initial_approved_name_matches', 'Название совпадает со старым импортом'),
    ('db_assignment', 'Действующее назначение в БД'),
    ('review_status', 'Статус проверки'),
]


def load_audit(path, engine):
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    actual_hash = hashlib.sha256(Path(engine).read_bytes()).hexdigest()
    if data['summary']['engine_sha256'] != actual_hash:
        raise ValueError('Rule source changed: rerun the Java probe first')
    records = data['records']
    if len({r['index'] for r in records}) != len(records):
        raise ValueError('Duplicate audit row index')
    keys = [(r['source_kind'], r['code']) for r in records]
    if len(set(keys)) != len(keys) or any(not key[1] for key in keys):
        raise ValueError('Missing or duplicate source-kind/code; resolve identity first')
    if dict(Counter(r['source_kind'] for r in records)) != data['summary']['source_counts']:
        raise ValueError('Source counts do not reconcile')
    return data


def annotate(records, proposals):
    proposals = dict(proposals)
    overrides = dict(proposals.get('overrides', {}))
    for group in proposals.get('groups', []):
        for code in group['codes']:
            key = f"{group.get('source_kind', 'PRODUCT')}:{code}"
            if key in overrides:
                raise ValueError(f'Duplicate proposal: {key}')
            overrides[key] = group['changes']
    proposals['overrides'] = overrides
    known = {(r['source_kind'], r['code']) for r in records}
    for key in proposals.get('overrides', {}):
        if tuple(key.split(':', 1)) not in known:
            raise ValueError(f'Proposal references missing row: {key}')
    result = []
    for original in records:
        r = dict(original)
        r.update(suggested_category=r['auto_category'], proposed_detail='',
                 priority='P3 — общий просмотр',
                 reason='Только проверка автоправил; отсутствие флагов не доказывает корректность.',
                 db_assignment='НЕ ПРОВЕРЕНО — нет актуального снимка БД',
                 review_status='Предложение; ручное подтверждение не выполнено')
        cat, name = r['auto_category'], r['name'].lower()
        if cat == 'UNMAPPED':
            r.update(priority='P1 — нет автокатегории', suggested_category='',
                     reason='Автоправило не сработало; это не доказывает отсутствие назначения в БД.')
        if cat == 'IPAD_MAC':
            r['proposed_detail'] = 'TABLETS' if 'ipad' in name else 'LAPTOPS'
        if cat == 'PODS_WATCH_OTHER_DEVICE':
            if 'watch' in name:
                r['proposed_detail'] = 'SMARTWATCHES'
            elif any(v in name for v in ('dualsense', 'dual sense', 'disc drive', 'дисковод')):
                r['proposed_detail'] = 'GAMING_ACCESSORIES'
            elif any(v in name for v in ('playstation', 'play station', 'ps5')):
                r['proposed_detail'] = 'GAME_CONSOLES'
            else:
                r['priority'] = 'P2 — состав смешанной категории'
        if cat in ('HEADPHONES_APPLE', 'HEADPHONES_SAMSUNG', 'HEADPHONES_OTHER'):
            r['proposed_detail'] = 'HEADPHONES / бренд отдельно; категорию сохранить'
        if cat == 'FITNESS_WEARABLE':
            r['proposed_detail'] = ('FITNESS_WEARABLE / спортивные часы' if 'garmin' in name
                                    else 'FITNESS_WEARABLE / браслет')
        if cat == 'OTHER_CASE':
            r.update(priority='P2 — совместимость',
                     reason='Не назначать iPhone/Samsung без доказательства; атрибуция отдельной продажи не меняет весь товар.')
        if (cat in ('IPHONE_NEW_ASIS', 'SAMSUNG_NEW') and 'Б/У' in r['source_group']):
            r.update(priority='P1 — проверить состояние',
                     reason='Группа источника Б/У, правило выбрало новое. Группа не является доказательством: проверить карточку/назначение.')
        if r['source_kind'] == 'SERVICE':
            if any(v in name for v in ('замена', 'ремонт', 'восстановление платы', 'подклей', 'прошив')):
                r['proposed_detail'] = 'REPAIR_SERVICE'
            elif 'диагност' in name:
                r['proposed_detail'] = 'DIAGNOSTICS_SERVICE'
            elif 'чистк' in name:
                r['proposed_detail'] = 'SETUP_SERVICE / чистка; отдельный подтип'
            elif 'сброс' in name:
                r['proposed_detail'] = 'SETUP_SERVICE / восстановление доступа'
            else:
                r.update(priority='P1 — смысл работы неясен',
                         reason='Короткое название работы: уточнить услуга это или запчасть, не выводить тип из одного слова.')
        r.update(proposals.get('overrides', {}).get(f"{r['source_kind']}:{r['code']}", {}))
        result.append(r)
    return result


def excel_column(number):
    result = ''
    while number:
        number, rem = divmod(number - 1, 26)
        result = chr(65 + rem) + result
    return result


def xml_text(value):
    # All cells are strings, never formulas; preserve product codes and leading zeros.
    return escape(re.sub(r'[\x00-\x08\x0b\x0c\x0e-\x1f]', '', str(value)))


def write_xlsx(path, sheets):
    if len({name for name, _ in sheets}) != len(sheets):
        raise ValueError('Duplicate worksheet name')
    for name, rows in sheets:
        if not 1 <= len(name) <= 31 or re.search(r'[\\/*?:\[\]]', name) or not rows:
            raise ValueError('Invalid worksheet')
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml',
                   '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                   '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                   '<Default Extension="xml" ContentType="application/xml"/>'
                   '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
                   '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
                   + ''.join(f'<Override PartName="/xl/worksheets/sheet{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
                             for i in range(1, len(sheets) + 1)) + '</Types>')
        z.writestr('_rels/.rels',
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
                   '</Relationships>')
        z.writestr('xl/workbook.xml', f'<workbook xmlns="{NS}" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>'
                   + ''.join(f'<sheet name={quoteattr(name)} sheetId="{i}" r:id="rId{i}"/>'
                             for i, (name, _) in enumerate(sheets, 1)) + '</sheets></workbook>')
        z.writestr('xl/_rels/workbook.xml.rels',
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   + ''.join(f'<Relationship Id="rId{i}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet{i}.xml"/>'
                             for i in range(1, len(sheets) + 1))
                   + '<Relationship Id="styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
                   '</Relationships>')
        z.writestr('xl/styles.xml', f'<styleSheet xmlns="{NS}">'
                   '<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font></fonts>'
                   '<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF24476B"/><bgColor indexed="64"/></patternFill></fill></fills>'
                   '<borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
                   '<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0"><alignment vertical="top" wrapText="1"/></xf></cellXfs>'
                   '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>')
        for i, (_, rows) in enumerate(sheets, 1):
            widths = [min(70, max(17, max(len(str(r[c])) for r in rows if len(r) > c) * .7))
                      for c in range(len(rows[0]))]
            data = f'<worksheet xmlns="{NS}"><dimension ref="A1:{excel_column(len(rows[0]))}{len(rows)}"/>'
            data += '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>'
            data += '<cols>' + ''.join(f'<col min="{j}" max="{j}" width="{w}" customWidth="1"/>' for j, w in enumerate(widths, 1)) + '</cols><sheetData>'
            for rownum, row in enumerate(rows, 1):
                data += f'<row r="{rownum}"' + (' ht="60" customHeight="1"' if rownum == 1 else '') + '>'
                data += ''.join(f'<c r="{excel_column(j)}{rownum}" s="{1 if rownum == 1 else 0}" t="inlineStr"><is><t xml:space="preserve">{xml_text(v)}</t></is></c>'
                                for j, v in enumerate(row, 1)) + '</row>'
            data += f'</sheetData><autoFilter ref="A1:{excel_column(len(rows[0]))}{len(rows)}"/></worksheet>'
            z.writestr(f'xl/worksheets/sheet{i}.xml', data)


def csv_cell(value):
    value = '' if value is None else str(value)
    return "'" + value if value.lstrip().startswith(('=', '+', '-', '@')) else value


def review_rows(records):
    return [[label for _, label in COLUMNS]] + [[r.get(key, '') if r.get(key) is not None else '' for key, _ in COLUMNS] for r in records]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--audit', type=Path, required=True)
    p.add_argument('--proposals', type=Path, required=True)
    p.add_argument('--engine', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True, help='New output directory; contains business data')
    args = p.parse_args()
    data = load_audit(args.audit, args.engine)
    proposals = json.loads(args.proposals.read_text(encoding='utf-8'))
    records = annotate(data['records'], proposals)
    args.output.mkdir(parents=True, exist_ok=False)
    products = [r for r in records if r['source_kind'] == 'PRODUCT']
    services = [r for r in records if r['source_kind'] == 'SERVICE']
    issues = sorted([r for r in records if not r['priority'].startswith('P3')], key=lambda r: (r['priority'], r['source_kind'], r['code']))
    category_counts = Counter(r['auto_category'] for r in products)
    categories = [['Категория', 'Назначение', 'Товары: результат автоправил', 'Работы: результат автоправил', 'Рекомендация']]
    for row in proposals['categories']:
        code, label, decision = row
        categories.append([code, label, category_counts[code], sum(r['auto_category'] == code for r in services), decision])
    missing = set(r['auto_category'] for r in records) - {r[0] for r in proposals['categories']}
    if missing:
        raise ValueError(f'Categories missing from registry: {missing}')
    summary = [['Проверка', 'Результат'],
               ['Товаров в выгрузке', len(products)], ['Работ в выгрузке', len(services)],
               ['Строк без автоправила', sum(r['auto_category'] == 'UNMAPPED' for r in records)],
               ['Строк с флагами P1/P2 (не число доказанных ошибок)', len(issues)],
               ['Проверено актуальных назначений БД', 0],
               ['Применено назначений / изменено продаж', 0],
               ['Контроль суммы групп автокатегорий', sum(category_counts.values())],
               ['Хеш исходника классификатора', data['summary']['engine_sha256']],
               ['Версия автоправил', data['summary']['rule_version']]]
    for priority, count in sorted(Counter(r['priority'] for r in records).items()):
        summary.append([priority, count])
    instructions = [['Раздел', 'Как пользоваться'],
                    ['Граница проверки', 'Выгрузка + текущий локальный Java-классификатор. НЕ действующая БД и НЕ список товаров без постоянного назначения.'],
                    ['Начало', 'Сначала Приоритетная проверка, затем Без автоправила, Новая структура и категории по очереди. Фильтры уже включены.'],
                    ['Предложения', 'Все новые категории и подтипы — проект, не сохранённые назначения. Пустое предложение означает недостаточно данных.'],
                    ['P3', 'Отсутствие выявленного сигнала риска, а не подтверждение правильности. Каждый товар остаётся доступен для ручного просмотра.'],
                    ['Старый импорт', 'Историческая справка: последующие миграции/ручные правки здесь не отражены. Нельзя импортировать её обратно.'],
                    ['Код', 'Код из Excel не равен гарантированно external_id БД. Перед применением сопоставить connection + external_id + code; конфликты останавливают операцию.'],
                    ['Зарплата', 'Уровни не менялись. Будущее изменение аналитики требует сравнения effective payroll defaults, а не только таблицы overrides.'],
                    ['Данные', 'Рабочая книга содержит названия и возможные серийные номера из ваших файлов. Хранить локально, не коммитить и не публиковать.'],
                    ['Воспроизводимость', 'rule-audit.json содержит хеши исходников; предложения отделены от результатов автоправил.']]
    sheets = [('Инструкция', instructions), ('Сводка', summary), ('Категории', categories),
              ('Приоритетная проверка', review_rows(issues)),
              ('Без автоправила', review_rows([r for r in records if r['auto_category'] == 'UNMAPPED'])),
              ('Новая структура', review_rows([r for r in records if r['proposed_detail']])),
              ('Все товары', review_rows(products)), ('Работы', review_rows(services))]
    write_xlsx(args.output / 'catalog-review.xlsx', sheets)
    with (args.output / 'catalog-review.csv').open('w', encoding='utf-8-sig', newline='') as f:
        csv.writer(f, delimiter=';').writerows([[csv_cell(v) for v in row] for row in review_rows(records)])
    (args.output / 'review.json').write_text(json.dumps({'summary': summary, 'records': records}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'products': len(products), 'services': len(services), 'flagged_rows': len(issues), 'writes_to_database': 0}))


if __name__ == '__main__':
    main()

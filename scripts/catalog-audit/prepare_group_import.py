#!/usr/bin/env python3
"""Prepare a review-only source-group import from Excel; no DB, HTTP or assignments."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from build_review import write_xlsx
from lexicon_audit import Lexicon
from probe_rules import read_xlsx

ROOT = Path(__file__).resolve().parents[2]


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(',', ':')).encode()).hexdigest()


def prepare_rows(exports, connection_key, lexicon):
    if not connection_key or connection_key != connection_key.strip():
        raise ValueError('An explicit connection key is required')
    if lexicon.connection_key != connection_key:
        raise ValueError('Lexicon connection scope mismatch')
    result, identities = [], set()
    for kind, rows in exports:
        if kind not in {'PRODUCT', 'SERVICE'} or not rows:
            raise ValueError('A nonempty export with explicit PRODUCT/SERVICE kind is required')
        for row in rows:
            if not {'Код', 'Наименование', 'Полная группа'} <= row.keys():
                raise ValueError('Export must include code, name and full source group columns')
            code, name, group_path = row['Код'], row['Наименование'], row['Полная группа']
            if (not isinstance(code, str) or not code.strip() or code != code.strip()
                    or not isinstance(name, str) or not name.strip() or not isinstance(group_path, str)):
                raise ValueError('Invalid export identity/name/group value')
            key = kind, code
            if key in identities:
                raise ValueError('Duplicate source-kind/code; resolve identity before import')
            identities.add(key)
            # Preserve raw group/name exactly. No inferred hierarchy or provider UUID.
            group_name = row.get('Название группы', '')
            if not isinstance(group_name, str):
                raise ValueError('Invalid group name')
            observed = lexicon.propose(name, kind, group_path)
            candidate = (observed['condition_source'] == 'OWNER_APPROVED_SOURCE_GROUP'
                         and observed['candidate_category'] == 'IPHONE_USED'
                         and observed['condition'] == 'USED' and observed['status'] != 'CONFLICT')
            record = {
                'source_kind': kind, 'source_code': code, 'expected_name': name,
                'source_group_path': group_path, 'source_group_name': group_name,
                'group_action': 'SET_CANDIDATE' if group_path.strip() else 'KEEP_EXISTING',
                'identity_status': 'NEEDS_DATABASE_MATCH',
                'database_product_id': None, 'provider_product_id': None,
                'database_group_verified': False,
                'group_review_reasons': ([] if group_path.strip() else ['EMPTY_GROUP_NOT_A_CLEAR_COMMAND'])
                                       + (['GROUP_NAME_MISSING'] if group_path.strip() and not group_name.strip() else []),
                'classification_review_reasons': observed['reasons'],
                'group_condition_proposal': ({'category': 'IPHONE_USED', 'condition': 'USED',
                                             'rule': 'owner_iphone_used_group'} if candidate else None),
            }
            record['observation_sha256'] = fingerprint({'connection_key': connection_key, **record})
            result.append(record)
            if len(result) > 10000:
                raise ValueError('Preview is limited to 10000 rows')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--products', type=Path, required=True)
    parser.add_argument('--services', type=Path)
    parser.add_argument('--connection-key', required=True)
    parser.add_argument('--output', type=Path, required=True, help='New directory inside ignored outputs/')
    args = parser.parse_args()
    if args.output.exists() or not args.output.resolve().is_relative_to(ROOT / 'outputs'):
        raise ValueError('Use a new directory inside ignored outputs/')
    sources = [(args.products, 'PRODUCT')]
    if args.services:
        sources.append((args.services, 'SERVICE'))
    source_hashes = {kind: hashlib.sha256(path.read_bytes()).hexdigest() for path, kind in sources}
    dictionary_path = Path(__file__).with_name('catalog_lexicon.json')
    dictionary = json.loads(dictionary_path.read_text())
    lexicon = Lexicon(dictionary, args.connection_key)
    records = prepare_rows([(kind, read_xlsx(path)) for path, kind in sources], args.connection_key, lexicon)
    # Reject an input changed during parsing rather than binding old hashes to new rows.
    if source_hashes != {kind: hashlib.sha256(path.read_bytes()).hexdigest() for path, kind in sources}:
        raise ValueError('Source export changed while preparing preview')
    summary = {
        'rows': len(records), 'source_counts': dict(Counter(r['source_kind'] for r in records)),
        'actions': dict(Counter(r['group_action'] for r in records)),
        'distinct_nonempty_paths': len({r['source_group_path'] for r in records if r['source_group_path'].strip()}),
        'group_condition_proposals': sum(r['group_condition_proposal'] is not None for r in records),
        'database_writes': 0, 'assignments_written': 0, 'can_apply': False,
        'live_database_verified': False,
    }
    payload = {
        'format_version': 1, 'kind': 'SOURCE_GROUP_IMPORT_PREVIEW', 'mode': 'PREVIEW_ONLY',
        'connection_key': args.connection_key, 'policy_version': dictionary['policy_version'],
        'source_sha256': source_hashes,
        'code_sha256': {name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
                       for name in ['prepare_group_import.py', 'catalog_lexicon.json', 'lexicon_audit.py',
                                    'probe_rules.py', 'build_review.py']},
        'export_observed_at': None, 'effective_from': None,
        'required_before_apply': ['FRESH_DATABASE_IDENTITY_AND_REVISION_CHECK',
                                 'SOURCE_OBSERVATION_DATE_REVIEW', 'EFFECTIVE_DATE_AND_HISTORY_POLICY',
                                 'MANUAL_ASSIGNMENT_CONFLICT_CHECK', 'EXPLICIT_CONFIRMATION'],
        'summary': summary, 'records': records,
    }
    payload['preview_sha256'] = fingerprint(payload)
    columns = ['source_kind', 'source_code', 'expected_name', 'source_group_path', 'source_group_name',
               'group_action', 'identity_status', 'group_review_reasons', 'classification_review_reasons']
    headers = ['Источник', 'Код — НЕ ID LiveSklad', 'Точное наименование', 'Полная группа из Excel',
               'Название группы из Excel', 'Предложение — НЕ изменение БД', 'Проверка идентичности',
               'Предупреждения группы', 'Предупреждения словаря']

    def table(rows):
        return [headers + ['Предложение правила Б/У — НЕ назначение']] + [
            [', '.join(r[c]) if isinstance(r[c], list) else r[c] for c in columns]
            + [r['group_condition_proposal']['category'] if r['group_condition_proposal'] else ''] for r in rows]

    sheets = [('Инструкция', [['Свойство', 'Значение'],
        ['Назначение', 'Предварительная проверка импорта групп. НЕ файл для действующего API импорта категорий.'],
        ['Идентичность', 'Код + тип + подключение + точное имя требуют сверки с актуальной БД. UUID не подставлены и не придуманы.'],
        ['Пустая группа', 'KEEP_EXISTING: не удалять ранее сохранённую группу. Отсутствующие в файле товары не удалять.'],
        ['SET_CANDIDATE', 'Группа есть в Excel; это ещё не решение применить её к карточке.'],
        ['История', 'Дата актуальности выгрузки и дата действия не определены. История продаж и зарплат не меняется.'],
        ['Правило Б/У', 'Только предложения локального словаря по одобренному подключению, после определения самого iPhone.'],
        ['Следующий шаг', 'Сверить БД, разрешить конфликты назначений и дат, утвердить preview; серверная команда применения пока не реализована.']]),
        ('Все строки', table(records)),
        ('Пустая группа', table([r for r in records if r['group_action'] == 'KEEP_EXISTING'])),
        ('Правило iPhone БУ', table([r for r in records if r['group_condition_proposal']])),
        ('Группы', [['Полный путь', 'Строк']] + [[path, count] for path, count in
                    sorted(Counter(r['source_group_path'] for r in records if r['source_group_path'].strip()).items())])]
    args.output.mkdir(parents=True)
    (args.output / 'source-group-import-preview.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    write_xlsx(args.output / 'source-group-import-preview.xlsx', sheets)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()

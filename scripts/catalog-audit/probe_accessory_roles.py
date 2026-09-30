#!/usr/bin/env python3
"""Replay Java target accessory roles over a local review, without assigning categories or writing a DB.

Legacy owner facts stay in the input report. This adapter cannot invent dated,
authorized confirmations or exclusive coverage from an old singleton target list.
"""
import argparse
import base64
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

from catalog_registry import REGISTRY, REGISTRY_PATH

ROOT = Path(__file__).resolve().parents[2]
JAVA_PRODUCT = ROOT / 'backend/src/main/java/com/storeanalytics/product'
JAVA_SOURCES = [JAVA_PRODUCT / path for path in (
    'model/ProductSourceKind.java', 'model/ProductConditionType.java',
    'model/AnalyticsCategoryKind.java', 'model/DeviceFamily.java',
    'model/CatalogCompatibilityEvidence.java', 'service/CatalogCategoryRegistry.java',
    'service/CatalogAccessoryAttachPolicy.java')]
JAVA_SOURCES.append(Path(__file__).with_name('CatalogAccessoryAuditProbe.java'))


def prepare_payload(report):
    summary = report['summary']
    connection = summary['connection_key']
    if not isinstance(connection, str) or not connection.strip():
        raise ValueError('Missing connection identity')
    if summary['category_registry_sha256'] != REGISTRY.sha256:
        raise ValueError('Registry changed: regenerate the local catalog review first')
    records, seen, lines = report['records'], set(), []
    if not records:
        raise ValueError('Empty catalog review')
    def encode(value):
        if not isinstance(value, str):
            raise ValueError('Expected text field')
        return base64.b64encode(value.encode('utf-8')).decode('ascii')
    for index, row in enumerate(records):
        key = row['source_kind'], row['code']
        if key in seen or key[0] not in {'PRODUCT', 'SERVICE'} or not key[1]:
            raise ValueError('Duplicate or unresolved catalog identity')
        seen.add(key)
        category = row['candidate_category']
        REGISTRY.require(category)
        if row['status'] not in {'PROPOSAL', 'NEEDS_REVIEW', 'CONFLICT', 'OWNER_CONFIRMED'}:
            raise ValueError('Unknown classification review status')
        targets = row['review_compatibility']
        if (not isinstance(targets, list) or any(not isinstance(t, str) or not t
                or any(c in t for c in ',\t\r\n') for t in targets) or len(targets) != len(set(targets))):
            raise ValueError('Malformed review targets')
        lines.append('\t'.join([str(index), encode(connection), key[0], encode(key[1]),
            encode(row['name']), encode(row['source_group']), category, ','.join(sorted(targets)), row['status']]))
    return '\n'.join(lines) + '\n'


def parse_results(output, records):
    lines = output.splitlines()
    if len(lines) != len(records):
        raise ValueError('Replay row count mismatch')
    results = []
    fields = ['policy_version', 'monetary_category', 'outcome', 'candidate_role',
              'reason', 'summary_group_hint', 'semantic_fingerprint']
    for index, line in enumerate(lines):
        cells = line.split('\t')
        if len(cells) != len(fields) + 1 or cells[0] != str(index):
            raise ValueError('Replay order or field count mismatch')
        row = records[index]
        if cells[2] != row['candidate_category']:
            raise ValueError('Role policy changed monetary category')
        results.append({key: row[key] for key in ('source_kind', 'code', 'name',
            'review_compatibility', 'review_compatibility_source')}
            | {'classification_review_status': row['status']} | dict(zip(fields, cells[1:])))
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--review', required=True, type=Path)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--evaluated-at', required=True, help='Explicit ISO-8601 timestamp; no implicit current time')
    parser.add_argument('--output', required=True, type=Path, help='New directory under ignored outputs/')
    args = parser.parse_args()
    if args.output.exists() or not args.output.resolve().is_relative_to(ROOT / 'outputs'):
        raise ValueError('Use a new directory under ignored outputs/')
    source_bytes = args.review.read_bytes()
    report = json.loads(source_bytes)
    payload = prepare_payload(report)
    with tempfile.TemporaryDirectory(prefix='catalog-accessory-probe-') as build:
        subprocess.run([str(args.java_home / 'bin/javac'), '-encoding', 'UTF-8', '-d', build,
                        *map(str, JAVA_SOURCES)], check=True, capture_output=True, text=True)
        process = subprocess.run([str(args.java_home / 'bin/java'), '-cp',
            os.pathsep.join([build, str(ROOT / 'backend/src/main/resources')]),
            'com.storeanalytics.product.service.CatalogAccessoryAuditProbe', args.evaluated_at],
            input=payload, capture_output=True, text=True, check=True)
    records = parse_results(process.stdout, report['records'])
    summary = {
        'scope': 'OFFLINE TARGET POLICY ONLY; not effective assignments, sale decisions or official metrics',
        'rows': len(records),
        'source_counts': dict(Counter(r['source_kind'] for r in records)),
        'outcomes': dict(Counter(r['outcome'] for r in records)),
        'candidate_roles': dict(Counter(r['candidate_role'] for r in records if r['candidate_role'])),
        'review_reasons': dict(Counter(r['reason'] for r in records if r['outcome'].startswith('REVIEW_'))),
        'policy_versions': sorted({r['policy_version'] for r in records}),
        'evaluated_at': args.evaluated_at,
        'input_sha256': hashlib.sha256(source_bytes).hexdigest(),
        'category_registry_sha256': REGISTRY.sha256,
        'implementation_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                  for p in [*JAVA_SOURCES, Path(__file__), REGISTRY_PATH]},
        'bound_confirmations_imported': 0,
        'legacy_owner_facts_preserved_in_input': True,
        'confirmation_metadata_migration_still_required': True,
        'defer_to_existing_is_not_zero': True,
        'database_writes': 0,
        'payroll_changes': False,
    }
    args.output.mkdir(parents=True)
    (args.output / 'accessory-role-proposals.json').write_text(
        json.dumps({'summary': summary, 'records': records}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in summary.items() if not k.endswith('sha256')}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()

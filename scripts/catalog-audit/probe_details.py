#!/usr/bin/env python3
"""Replay the proposed tablet/laptop/watch detail policy locally; never write assignments."""
import argparse
import base64
from collections import Counter
import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

from probe_rules import read_xlsx
from build_review import csv_cell


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--products', type=Path, required=True)
    p.add_argument('--services', type=Path, required=True)
    p.add_argument('--java-home', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True, help='New local business-data output directory')
    args = p.parse_args()
    if args.output.exists():
        raise ValueError('Use a new output directory; previous audit is immutable')
    root = Path(__file__).resolve().parents[2]
    sources = [(args.products, 'PRODUCT'), (args.services, 'SERVICE')]
    records = []
    for path, kind in sources:
        for row in read_xlsx(path):
            if not row['Код'] or not row['Наименование']:
                raise ValueError('Missing product code or name')
            records.append({'index': len(records), 'source_kind': kind, 'code': row['Код'],
                            'name': row['Наименование'], 'source_group': row.get('Полная группа', '')})
    if len({(r['source_kind'], r['code']) for r in records}) != len(records):
        raise ValueError('Duplicate identity in export')
    product = root / 'backend/src/main/java/com/storeanalytics/product'
    java_sources = [product / 'model/ProductSourceKind.java', product / 'model/ProductConditionType.java',
                    product / 'model/AnalyticsCategoryKind.java', product / 'model/DeviceFamily.java',
                    product / 'service/CatalogCategoryRegistry.java',
                    product / 'service/CatalogDeviceCategoryPolicy.java',
                    product / 'service/CatalogDeviceDetailProposer.java',
                    Path(__file__).with_name('CatalogDetailAuditProbe.java')]
    def encode(value):
        return base64.b64encode(value.encode()).decode()
    payload = ''.join(f"{r['index']}\t{encode(r['name'])}\t{r['source_kind']}\t{encode(r['source_group'])}\n" for r in records)
    with tempfile.TemporaryDirectory(prefix='catalog-detail-probe-') as build:
        subprocess.run([str(args.java_home / 'bin/javac'), '-encoding', 'UTF-8', '-d', build,
                        *map(str, java_sources)], check=True, capture_output=True, text=True)
        process = subprocess.run([str(args.java_home / 'bin/java'), '-cp',
                                  os.pathsep.join([build, str(root / 'backend/src/main/resources')]),
                                  'com.storeanalytics.product.service.CatalogDetailAuditProbe'],
                                 input=payload, capture_output=True, text=True, check=True)
    lines = process.stdout.splitlines()
    if len(lines) != len(records):
        raise ValueError('Replay count mismatch')
    fields = ['policy_version', 'fingerprint', 'status', 'device_types', 'brand_candidates',
              'condition', 'candidate_category', 'reason_codes', 'evidence']
    for index, line in enumerate(lines):
        values = line.split('\t')
        if len(values) != len(fields) + 1 or int(values[0]) != index:
            raise ValueError('Invalid replay output')
        records[index].update(zip(fields, values[1:]))
    relevant = [r for r in records if r['device_types']]
    summary = {
        'scope': 'Read-only shadow proposal for tablets/laptops/watches; NOT effective DB assignments',
        'source_counts': dict(Counter(r['source_kind'] for r in records)),
        'rows_with_device_type_evidence': len(relevant),
        'candidate_categories': dict(Counter(r['candidate_category'] for r in relevant)),
        'relevant_statuses': dict(Counter(r['status'] for r in relevant)),
        'source_sha256': {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path, _ in sources},
        'java_source_sha256': {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in java_sources},
        'category_registry_sha256': hashlib.sha256(
            (root / 'backend/src/main/resources/catalog/category-registry-v1.tsv').read_bytes()).hexdigest(),
        'assignments_written': 0,
    }
    args.output.mkdir(parents=True)
    (args.output / 'detail-proposals.json').write_text(
        json.dumps({'summary': summary, 'records': records}, ensure_ascii=False, indent=2), encoding='utf-8')
    columns = ['code', 'name', 'source_kind', 'source_group', *fields]
    with (args.output / 'device-review.csv').open('w', encoding='utf-8-sig', newline='') as f:
        writer = csv.writer(f, delimiter=';')
        writer.writerow(columns)
        writer.writerows([[csv_cell(r[key]) for key in columns] for r in relevant])
    print(json.dumps({key: value for key, value in summary.items() if not key.endswith('sha256')}, ensure_ascii=False))


if __name__ == '__main__':
    main()

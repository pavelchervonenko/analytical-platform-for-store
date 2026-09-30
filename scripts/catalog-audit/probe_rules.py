#!/usr/bin/env python3
"""Read LiveSklad XLSX exports and run current Java rule source, without DB access.

Requires backend classes already compiled and a Spring context jar on the supplied
classpath. Historical assignment exports, when supplied, are references only.
"""
import argparse
import base64
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import posixpath
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
REL = '{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id'


def read_xlsx(path):
    with zipfile.ZipFile(path) as z:
        shared = []
        if 'xl/sharedStrings.xml' in z.namelist():
            shared = [''.join(t.text or '' for t in si.findall('.//m:t', NS))
                      for si in ET.fromstring(z.read('xl/sharedStrings.xml')).findall('m:si', NS)]
        book = ET.fromstring(z.read('xl/workbook.xml'))
        sheets = book.findall('m:sheets/m:sheet', NS)
        if len(sheets) != 1:
            raise ValueError('Expected a single export worksheet; select it explicitly before audit')
        rels = ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))
        target = next(r.attrib['Target'] for r in rels if r.attrib['Id'] == sheets[0].attrib[REL])
        part = posixpath.normpath(target.lstrip('/') if target.startswith('/') else 'xl/' + target)
        rows = []
        for row in ET.fromstring(z.read(part)).findall('.//m:sheetData/m:row', NS):
            values = {}
            for cell in row.findall('m:c', NS):
                if cell.find('m:f', NS) is not None:
                    raise ValueError('Formula in source export; export literal values for audit')
                column = re.sub(r'\d', '', cell.attrib['r'])
                value = cell.find('m:v', NS)
                text = (value.text or '') if value is not None else ''
                if cell.get('t') == 's':
                    text = shared[int(text)] if text else ''
                elif cell.get('t') == 'inlineStr':
                    text = ''.join(t.text or '' for t in cell.findall('.//m:t', NS))
                values[column] = text
            if any(values.values()):
                rows.append(values)
        if not rows:
            raise ValueError('Empty export')
        headers = rows.pop(0)
        if len(set(headers.values())) != len(headers):
            raise ValueError('Duplicate headers')
        if not {'Наименование', 'Код'} <= set(headers.values()):
            raise ValueError('Required headers: Наименование and Код')
        return [{label: row.get(column, '') for column, label in headers.items()} for row in rows]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--products', type=Path, required=True)
    parser.add_argument('--services', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--historical-assignments', type=Path, action='append', default=[])
    parser.add_argument('--output', type=Path, required=True, help='New JSON output file; business data')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    engine = root / 'backend/src/main/java/com/storeanalytics/product/service/ProductAutoClassificationRuleEngine.java'
    product_sources = root / 'backend/src/main/java/com/storeanalytics/product'
    classification_sources = [engine, *(product_sources / path for path in (
        'service/ProductAutoClassificationDecision.java', 'service/CatalogCategoryRegistry.java',
        'model/AnalyticsCategoryKind.java', 'model/DeviceFamily.java', 'model/ProductConditionType.java'))]
    records = []
    sources = [(args.products, 'PRODUCT'), (args.services, 'SERVICE')]
    for file, kind in sources:
        for row in read_xlsx(file):
            if not row['Код'] or not row['Наименование']:
                raise ValueError('Row without identity or name')
            records.append({'index': len(records), 'source_kind': kind, 'code': row['Код'],
                            'name': row['Наименование'], 'source_group': row.get('Полная группа', '')})
    keys = [(r['source_kind'], r['code']) for r in records]
    if len(set(keys)) != len(keys):
        raise ValueError('Duplicate source-kind/code: identity review required')
    with tempfile.TemporaryDirectory(prefix='catalog-rule-audit-') as build:
        subprocess.run([str(args.java_home / 'bin/javac'), '-encoding', 'UTF-8', '-cp', args.classpath,
                        '-d', build, *map(str, classification_sources),
                        str(Path(__file__).with_name('CatalogAuditProbe.java'))],
                       check=True, capture_output=True, text=True)
        payload = ''.join(f"{r['index']}\t{base64.b64encode(r['name'].encode()).decode()}\t{r['source_kind']}\n" for r in records)
        result = subprocess.run([str(args.java_home / 'bin/java'), '-cp',
                                os.pathsep.join([build, str(root / 'backend/src/main/resources'), args.classpath]),
                                 'com.storeanalytics.product.service.CatalogAuditProbe'], input=payload,
                                check=True, capture_output=True, text=True)
    decisions = result.stdout.splitlines()
    if len(decisions) != len(records):
        raise ValueError('Rule output count mismatch')
    for expected, line in enumerate(decisions):
        index, category, condition, rule = line.split('\t')
        if int(index) != expected:
            raise ValueError('Rule output order mismatch')
        records[expected].update(auto_category=category, auto_condition=condition, auto_rule=rule)
    historical = {}
    for file in args.historical_assignments:
        for row in json.loads(file.read_text(encoding='utf-8'))['assignments']:
            historical[row['externalProductId']] = row
    for row in records:
        # Historical files use numeric catalog codes, not actual provider UUIDs.
        old = historical.get(row['code']) if row['source_kind'] == 'PRODUCT' else None
        row['initial_approved_category'] = old['categoryCode'] if old else ''
        row['initial_approved_name_matches'] = old['productName'] == row['name'] if old else None
    summary = {
        'scope': 'Export plus local automatic rules only; NOT production assignments or sales',
        'rule_version': re.search(r'RULE_VERSION = "([^"]+)"', engine.read_text()).group(1),
        'engine_sha256': hashlib.sha256(engine.read_bytes()).hexdigest(),
        'category_registry_sha256': hashlib.sha256(
            (root / 'backend/src/main/resources/catalog/category-registry-v1.tsv').read_bytes()).hexdigest(),
        'sources': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p, _ in sources},
        'historical_sources': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in args.historical_assignments},
        'source_counts': dict(Counter(r['source_kind'] for r in records)),
        'product_categories': dict(Counter(r['auto_category'] for r in records if r['source_kind'] == 'PRODUCT')),
        'service_categories': dict(Counter(r['auto_category'] for r in records if r['source_kind'] == 'SERVICE')),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as f:
        json.dump({'summary': summary, 'records': records}, f, ensure_ascii=False, indent=2)
    print(json.dumps({'counts': summary['source_counts'], 'writes_to_database': 0}))


if __name__ == '__main__':
    main()

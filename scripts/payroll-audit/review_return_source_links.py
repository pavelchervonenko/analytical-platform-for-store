#!/usr/bin/env python3
"""Validate retained-source links against an earlier catalog snapshot; never recover data."""
import argparse
from collections import Counter
from decimal import Decimal
import json
import os
from pathlib import Path

from reconcile_snapshot import load_snapshot, sha


def verify_export(path):
    checksum = path.with_name('snapshot.sha256').read_text().split()
    if (checksum != [sha(path), 'snapshot.jsonl']
            or path.with_name('export.stderr').read_bytes()):
        raise ValueError('Checksum or export diagnostics mismatch')
    rows = [json.loads(line, parse_float=Decimal) for line in path.read_text().splitlines()]
    if (len(rows) != 8 or rows[0].get('kind') != 'snapshot_start'
            or rows[-1].get('kind') != 'snapshot_end'
            or rows[0].get('contract') != 'payroll-return-source-links-v1'
            or rows[0].get('role_guard') != 1
            or not rows[0].get('snapshot_at')
            or rows[0]['snapshot_at'] != rows[-1].get('snapshot_at')
            or any(r.get('read_only') != 'on' for r in (rows[0], rows[-1]))):
        raise ValueError('Incomplete or unexpected export boundaries')
    return rows[1:-1], rows[0]['snapshot_at']


def review(rows, tables):
    docs = {d['id']: d for d in tables['sales_documents']}
    items = {i['id']: i for i in tables['sales_document_items']}
    products = {p['id']: p for p in tables['products']}
    expected = {d['external_id'] for d in docs.values() if d['document_kind'] == 'RETURN'
                and not d['is_deleted'] and d['original_document_id'] is None}
    ids = [r['target_external_id'] for r in rows]
    if len(set(ids)) != len(ids) or set(ids) != expected:
        raise ValueError('Exact return target coverage differs from baseline')
    result = []
    for row in rows:
        doc = docs.get(row.get('return_id'))
        if (row.get('kind') != 'return_source_link' or doc is None
                or row.get('return_kind') != 'RETURN' or row.get('return_deleted') is not False
                or row.get('original_document_id') is not None
                or row.get('employee_id') != doc['employee_id']
                or row['target_external_id'] != doc['external_id']
                or any(row.get(k) != doc[k] for k in ('connection_id', 'store_id', 'business_date'))):
            raise ValueError('Return identity/state changed; re-review before proceeding')
        expected_items = {i['id'] for i in items.values() if i['sales_document_id'] == doc['id']}
        observed_items = [i['item_id'] for i in row['items']]
        if set(observed_items) != expected_items or len(set(observed_items)) != len(observed_items):
            raise ValueError('Return item coverage changed')
        for item in row['items']:
            old = items[item['item_id']]
            product = products[old['product_id']]
            if (any(item.get(k) != old[k] for k in ('product_id', 'external_id', 'original_item_id'))
                    or item['item_deleted'] != old['is_deleted'] or item['item_deleted']):
                raise ValueError('Return item identity/state changed')
            for key in ('quantity', 'net_amount', 'cost_amount'):
                a, b = item[key], old[key]
                if (a is None) != (b is None) or (a is not None and Decimal(a) != Decimal(b)):
                    raise ValueError('Return amounts changed')
            issues = []
            source = None
            if not row.get('raw_id'):
                issues.append('NO_RETAINED_SOURCE')
            if not row.get('source_parent_id'):
                issues.append('SOURCE_PARENT_MISSING')
            if (row.get('source_positions_are_array') is not True
                    or item['source_position_match_count'] != 1 or len(item['source_positions']) != 1):
                issues.append('SOURCE_POSITION_MISSING_OR_AMBIGUOUS')
            else:
                source = item['source_positions'][0]
                if not source.get('original_position_external_id'):
                    issues.append('SOURCE_ORIGINAL_POSITION_MISSING')
                if source.get('product_external_id') != product['external_id']:
                    issues.append('SOURCE_PRODUCT_MISMATCH')
            if row.get('source_parent_id') and row['parent_id_in_db'] is None:
                issues.append('ORIGINAL_SALE_NOT_IN_DATABASE')
            elif row['parent_id_in_db'] is not None:
                # Not a recovery approval, even when all observed links can be resolved.
                issues.append('PARENT_STATE_REQUIRES_FRESH_PREFLIGHT')
            result.append(dict(
                code=product['code'], document_id=doc['id'], item_id=item['item_id'],
                return_external_id=row['target_external_id'], store_id=row['store_id'],
                connection_id=row['connection_id'], business_date=row['business_date'],
                source_parent_external_id=row.get('source_parent_id'),
                source_original_position_external_id=source.get('original_position_external_id') if source else None,
                source_product_matches=source is not None and source.get('product_external_id') == product['external_id'],
                original_sale_absent_in_baseline=not any(
                    d['external_id'] == row.get('source_parent_id') and d['connection_id'] == row['connection_id']
                    for d in docs.values()),
                raw_first_seen_at=row.get('raw_first_seen_at'), raw_last_seen_at=row.get('raw_last_seen_at'),
                source_is_live_api=False, amounts_unchanged=True, issues=issues,
                recovery_approved=False, applied=False))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'baseline', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    rows, at = verify_export(args.source)
    tables, _, _ = load_snapshot(args.baseline)
    results = review(rows, tables)
    summary = dict(return_lines=len(results), issue_counts=dict(Counter(
        issue for row in results for issue in row['issues'])))
    report = dict(scope='REVIEW_ONLY', snapshot_at=at, source_sha256=sha(args.source),
                  baseline_sha256=sha(args.baseline), analyzer_sha256=sha(Path(__file__)),
                  summary=summary, rows=results, applied=False, effective_from=None,
                  limitation='Retained source only; original sale date/status/amounts require LiveSklad read-only verification before separate recovery approval.')
    fd = os.open(args.output, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2, default=str)
        stream.write('\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()

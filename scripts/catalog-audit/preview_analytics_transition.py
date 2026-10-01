#!/usr/bin/env python3
"""Read-only analytical transition candidates. Never an executable correction manifest."""
from __future__ import annotations

import argparse
import csv
from collections import Counter, defaultdict
from datetime import date
import hashlib
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/payroll-audit'))
from reconcile_snapshot import load_snapshot


def preview(tables, review, registry, start, end):
    start = date.fromisoformat(start).isoformat()
    end = date.fromisoformat(end).isoformat()
    if start >= end:
        raise ValueError('Expected a nonempty half-open business-date interval')
    connections = {r['id']: r['connection_key'] for r in tables['integration_connections']}
    products = {r['id']: r for r in tables['products']}
    documents = {r['id']: r for r in tables['sales_documents']}
    items = {r['id']: r for r in tables['sales_document_items']}
    categories = {r['id']: r for r in tables['analytics_categories']}
    reviewed = {}
    for row in review['records']:
        key = (row['source_kind'], row['code'])
        if key in reviewed:
            raise ValueError('Duplicate review identity')
        reviewed[key] = row
    identities = Counter((p['connection_id'], p['source_kind'], p.get('code'))
                         for p in products.values())
    linked_returns = defaultdict(list)
    for returned in items.values():
        returned_document = documents[returned['sales_document_id']]
        if (not returned['is_deleted'] and not returned_document['is_deleted']
                and returned_document['document_kind'] == 'RETURN' and returned.get('original_item_id')):
            linked_returns[returned['original_item_id']].append(returned_document)
    records = []
    for item in items.values():
        document = documents[item['sales_document_id']]
        if item['is_deleted'] or document['is_deleted'] or not start <= document['business_date'] < end:
            continue
        product = products[item['product_id']]
        current = categories[item['analytics_category_id']]['code']
        reasons = []
        target = None
        decision_source = None
        key = (product['source_kind'], product.get('code'))
        row = reviewed.get(key)
        if connections[product['connection_id']] != review['summary']['connection_key']:
            reasons.append('CONNECTION_MISMATCH')
        elif document['connection_id'] != product['connection_id']:
            reasons.append('FACT_PRODUCT_CONNECTION_MISMATCH')
        elif not product.get('code') or identities[(product['connection_id'], *key)] != 1:
            reasons.append('AMBIGUOUS_CATALOG_IDENTITY')
        elif row is None:
            reasons.append('NO_REVIEWED_IDENTITY')
        elif product['name'] != row['name']:
            reasons.append('PRODUCT_OBSERVATION_CHANGED')
        else:
            target = row.get('owner_confirmed_category') or row.get('candidate_category')
            decision_source = ('OWNER_CONFIRMED' if row.get('owner_confirmed_category')
                               else 'LEXICON_PROPOSAL')
            if target not in registry:
                reasons.append('UNKNOWN_TARGET_CATEGORY')
                target = None
            elif registry[target]['scope'] == 'DEFERRED':
                reasons.append('DEFERRED_CATEGORY_NOT_IN_RELEASE')
                target = None
            elif target != current and decision_source != 'OWNER_CONFIRMED':
                # A lexical match is not a recorded owner decision. Recover previous
                # approvals before asking the owner again; never bulk-apply guesses.
                reasons.append('DECISION_EVIDENCE_REQUIRED')
        original_id = document.get('original_document_id')
        original_item_id = item.get('original_item_id')
        original = documents.get(original_id)
        original_item = items.get(original_item_id)
        if document['document_kind'] == 'RETURN':
            if original is None or original_item is None:
                reasons.append('ORIGINAL_NOT_IN_SNAPSHOT_OR_NOT_LINKED')
            elif (original['document_kind'] != 'SALE' or original['is_deleted']
                  or original_item['is_deleted']
                  or original_item['sales_document_id'] != original_id
                  or original_item['product_id'] != product['id']
                  or original['store_id'] != document['store_id']
                  or original['connection_id'] != document['connection_id']):
                reasons.append('ORIGINAL_LINK_REQUIRES_REVIEW')
            elif not start <= original['business_date'] < end:
                reasons.append('ORIGINAL_OUTSIDE_SELECTED_PERIOD')
        elif any(not start <= related['business_date'] < end for related in linked_returns[item['id']]):
            reasons.append('RELATED_RETURN_OUTSIDE_SELECTED_PERIOD')
        records.append(dict(
            item_id=item['id'], document_id=document['id'], product_id=product['id'],
            product_external_id=product['external_id'], code=product.get('code'),
            store_id=document['store_id'], business_date=document['business_date'],
            document_kind=document['document_kind'], current_category=current,
            candidate_category=target, decision_source=decision_source,
            original_document_id=original_id, original_item_id=original_item_id,
            expected_item_version=item.get('version'), expected_product_version=product.get('version'),
            category_change=target is not None and target != current,
            reasons=reasons, requires_payroll_preflight=target is not None and target != current,
        ))
    records.sort(key=lambda r: (r['store_id'], r['business_date'], r['document_id'], r['item_id']))
    transitions = Counter((r['current_category'], r['candidate_category'])
                          for r in records if r['category_change'])
    return dict(summary=dict(
        contract='analytical-transition-preview-v1', scope='CANDIDATES_ONLY_NOT_APPLY_MANIFEST',
        period_start=start, period_end_exclusive=end, item_count=len(records),
        candidate_changed_lines=sum(r['category_change'] for r in records),
        candidate_changed_products=len({r['product_id'] for r in records if r['category_change']}),
        reasons=dict(Counter(reason for r in records for reason in r['reasons'])),
        assignments_written=0, payroll_changes_applied=0, historical_facts_written=0,
        return_scope_expanded=False, payroll_preflight_completed=False,
    ), transitions=[dict(before=a, candidate=b, lines=n) for (a, b), n in sorted(transitions.items())],
        records=records)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--review', type=Path, required=True)
    parser.add_argument('--start', required=True)
    parser.add_argument('--end-exclusive', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    tables, first, _ = load_snapshot(args.snapshot)
    review = json.loads(args.review.read_text())
    registry_path = ROOT / 'backend/src/main/resources/catalog/category-registry-v2.tsv'
    registry_sha = hashlib.sha256(registry_path.read_bytes()).hexdigest()
    if review['summary'].get('category_registry_sha256') != registry_sha:
        raise ValueError('Review taxonomy differs from current registry; rebuild and verify the review')
    with registry_path.open() as source:
        next(source)
        registry = {r['code']: r for r in csv.DictReader(source, delimiter='\t')}
    result = preview(tables, review, registry, args.start, args.end_exclusive)
    result['summary'].update(snapshot_at=first['snapshot_at'], input_sha256={
        name: hashlib.sha256(path.read_bytes()).hexdigest()
        for name, path in [('snapshot', args.snapshot), ('review', args.review), ('registry', registry_path)]
    })
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    (args.output / 'preview.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result['summary'], ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Offline investigation of unlinked returns. Candidates are never approved links."""
import argparse
from collections import Counter
from datetime import datetime
from decimal import Decimal
import json
import os
from pathlib import Path

from reconcile_snapshot import load_snapshot, sha


def instant(value):
    # Python 3.10 fromisoformat rejects PostgreSQL fractions with 1/2/4/5 digits.
    pattern = '%Y-%m-%dT%H:%M:%S.%f%z' if '.' in value else '%Y-%m-%dT%H:%M:%S%z'
    return datetime.strptime(value, pattern)


def candidates_for(returned, document, items, documents):
    """Same identity, store and prior time only; amounts do not prove identity."""
    result = []
    for item in items:
        sale = documents[item['sales_document_id']]
        if (item['is_deleted'] or sale['is_deleted']
                or sale['document_kind'] != 'SALE'
                or item['product_id'] != returned['product_id']
                or sale['store_id'] != document['store_id']
                or sale['connection_id'] != document['connection_id']
                or instant(sale['occurred_at'])
                > instant(document['occurred_at'])):
            continue
        same_quantity_net = all(Decimal(item[k]) == Decimal(returned[k])
                                for k in ('quantity', 'net_amount'))
        known_equal_cost = (item['cost_amount'] is not None
                            and returned['cost_amount'] is not None
                            and Decimal(item['cost_amount']) == Decimal(returned['cost_amount']))
        result.append(dict(document_id=sale['id'], item_id=item['id'],
                           external_document_id=sale['external_id'],
                           external_item_id=item['external_id'],
                           business_date=sale['business_date'],
                           quantity=item['quantity'], net_amount=item['net_amount'],
                           cost_amount=item['cost_amount'],
                           same_quantity_net=same_quantity_net,
                           same_quantity_net_known_cost=same_quantity_net and known_equal_cost,
                           link_confirmed=False))
    return sorted(result, key=lambda r: (r['business_date'], r['item_id']))


def review(tables):
    documents = {d['id']: d for d in tables['sales_documents']}
    products = {p['id']: p for p in tables['products']}
    result = []
    for item in tables['sales_document_items']:
        document = documents[item['sales_document_id']]
        if (item['is_deleted'] or document['is_deleted']
                or document['document_kind'] != 'RETURN'
                or (item['original_item_id'] is not None
                    and document['original_document_id'] is not None)):
            continue
        candidates = candidates_for(item, document, tables['sales_document_items'], documents)
        result.append(dict(
            code=products[item['product_id']]['code'], product_id=item['product_id'],
            document_id=document['id'], item_id=item['id'],
            external_document_id=document['external_id'], external_item_id=item['external_id'],
            store_id=document['store_id'], connection_id=document['connection_id'],
            business_date=document['business_date'], source_status=document['source_status'],
            original_document_id=document['original_document_id'],
            original_item_id=item['original_item_id'], employee_missing=document['employee_id'] is None,
            quantity=item['quantity'], net_amount=item['net_amount'], cost_amount=item['cost_amount'],
            candidates=candidates, candidate_count=len(candidates),
            quantity_net_matches=sum(c['same_quantity_net'] for c in candidates),
            quantity_net_cost_matches=sum(c['same_quantity_net_known_cost'] for c in candidates),
            review_status='SOURCE_LINK_EVIDENCE_REQUIRED', applied=False))
    return sorted(result, key=lambda r: (r['business_date'], r['item_id']))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    tables, start, _ = load_snapshot(args.snapshot)
    rows = review(tables)
    report = dict(scope='REVIEW_ONLY', snapshot_sha256=sha(args.snapshot),
                  analyzer_sha256=sha(Path(__file__)), snapshot_at=start['snapshot_at'],
                  effective_from=None, applied=False, returns=rows,
                  summary=dict(return_lines=len(rows), no_prior_candidates=sum(
                      r['candidate_count'] == 0 for r in rows),
                      source_status=dict(Counter(r['source_status'] for r in rows))),
                  limitation='Candidates are not source links. No assignment or recovery is authorized.')
    args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2, default=str)
        stream.write('\n')
    print(json.dumps(report['summary'], ensure_ascii=False))


if __name__ == '__main__':
    main()

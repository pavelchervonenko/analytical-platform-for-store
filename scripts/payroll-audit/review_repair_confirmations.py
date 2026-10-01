#!/usr/bin/env python3
"""Validate exact owner decisions and calculate an offline D-019 scenario only."""
import argparse
from collections import defaultdict
from decimal import Decimal
import json
import os
from pathlib import Path

from reconcile_snapshot import load_snapshot, sha, shadow_month


def validate(payload, tables, snapshot_sha):
    if (payload.get('contract') != 'payroll-repair-fact-confirmations-v1'
            or payload.get('scope') != 'REVIEW_ONLY'
            or payload.get('snapshot_sha256') != snapshot_sha
            or payload.get('effective_from') is not None or payload.get('applied') is not False):
        raise ValueError('Confirmation scope/hash mismatch')
    items = {i['id']: i for i in tables['sales_document_items']}
    docs = {d['id']: d for d in tables['sales_documents']}
    products = {p['id']: p for p in tables['products']}
    seen, zero_cost, zero_price = set(), set(), set()
    for row in payload['records']:
        item = items.get(row['item_id'])
        if item is None or row['item_id'] in seen:
            raise ValueError('Missing or duplicate confirmed fact')
        seen.add(item['id'])
        doc, product = docs[item['sales_document_id']], products[item['product_id']]
        expected = dict(item_external_id=item['external_id'], document_id=doc['id'],
                        document_external_id=doc['external_id'], connection_id=doc['connection_id'],
                        store_id=doc['store_id'], product_id=product['id'],
                        product_external_id=product['external_id'], code=product['code'],
                        business_date=doc['business_date'], observed_item_version=item['version'],
                        observed_item_updated_at=item['updated_at'])
        if (any(row.get(k) != v for k, v in expected.items()) or item['is_deleted']
                or doc['is_deleted'] or doc['document_kind'] != 'SALE'
                or row.get('applied') is not False or row.get('effective_from') is not None
                or row.get('historical_correction_approved') is not False
                or row.get('payroll_exclusion_approved') is not False):
            raise ValueError('Confirmed fact identity/state mismatch')
        for key in ('quantity', 'net_amount', 'cost_amount'):
            if row[key] is None or item[key] is None or Decimal(row[key]) != Decimal(item[key]):
                raise ValueError('Confirmed amounts differ from snapshot')
        if row['decision_id'] == 'D-018':
            if (Decimal(row['cost_amount']) != 0 or row.get('full_cost_confirmed') is not True
                    or row.get('payroll_basis_status') != 'CONFIRMED_ZERO_COST'):
                raise ValueError('Invalid zero-cost decision')
            zero_cost.add(item['id'])
        elif row['decision_id'] == 'D-019':
            if (Decimal(row['net_amount']) != 0 or row.get('purpose') != 'FREE_OR_WARRANTY_REPAIR'
                    or row.get('payroll_category') != 'PAID_REPAIR'
                    or row.get('full_cost_confirmed') is not False
                    or row.get('payroll_basis_status') != 'CONFIRMED_ZERO_PRICE_ZERO_REWARD'
                    or row.get('payroll_reward_base') != '0.00'
                    or row.get('analytics_cost_preserved') is not True):
                raise ValueError('Invalid zero-price decision')
            zero_price.add(item['id'])
        else:
            raise ValueError('Unknown decision')
    if len(zero_cost) != 4 or len(zero_price) != 3:
        raise ValueError('Expected four cost and three purpose/reward confirmations')
    return zero_cost, zero_price


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('snapshot', 'reconciliation', 'previous_review', 'remainder', 'confirmations', 'output'):
        parser.add_argument('--' + name.replace('_', '-'), type=Path, required=True)
    args = parser.parse_args()
    tables, _, _ = load_snapshot(args.snapshot)
    payload = json.loads(args.confirmations.read_text())
    zero_cost, zero_price = validate(payload, tables, sha(args.snapshot))
    rec = json.loads(args.reconciliation.read_text())
    previous = json.loads(args.previous_review.read_text())
    remainder = json.loads(args.remainder.read_text())
    if (rec['summary']['snapshot_sha256'] != sha(args.snapshot)
            or payload['review_sha256'] != sha(args.previous_review)
            or previous['summary']['reconciliation_sha256'] != sha(args.reconciliation)
            or remainder['summary']['previous_review_sha256'] != sha(args.previous_review)
            or remainder['summary']['reconciliation_sha256'] != sha(args.reconciliation)
            or remainder['summary']['snapshot_sha256'] != sha(args.snapshot)
            or zero_cost != {f['item_id'] for f in previous['cost_review']}
            or zero_price != {f['item_id'] for f in previous['negative_profit_review']}):
        raise ValueError('Reports/confirmation scope do not match')
    extra = {r['product_id']: r['target_salary'] for r in remainder['additional_changes']}
    months = defaultdict(list)
    for fact in rec['facts']:
        for key in ('net_amount', 'cost_amount', 'quantity'):
            fact[key] = Decimal(fact[key]) if fact[key] is not None else None
        fact['expanded'] = extra.get(fact['product_id'], fact['after'])
        months[(fact['store_id'], fact['business_date'][:7])].append(fact)
    plans = {(p['store_id'], p['plan_month'][:7]): p for p in tables['store_performance_plans']}
    impact = []
    for (sid, month), facts in sorted(months.items()):
        ids = zero_price & {f['item_id'] for f in facts}
        if not ids:
            continue
        schemes = [s for s in tables['payroll_schemes'] if s['effective_from'] <= month + '-01']
        scheme = max(schemes, key=lambda s: s['effective_from']) if schemes else None
        before = shadow_month(facts, 'expanded', scheme, plans.get((sid, month)))
        after = shadow_month(facts, 'expanded', scheme, plans.get((sid, month)), ids)
        impact.append(dict(store_id=sid, month=month, affected_lines=len(ids), before=before,
                           after=after, fund_delta=after['fund'] - before['fund']
                           if after['fund'] is not None and before['fund'] is not None else None))
    products = {p['id']: p for p in tables['products']}
    identity = []
    for row in previous['renamed_products']:
        if not row['unit_marker_changed']:
            continue
        product = products[row['product_id']]
        facts = [f for fs in months.values() for f in fs if f['product_id'] == product['id']]
        identity.append(dict(row, source_kind=product['source_kind'],
                             external_id=product['external_id'],
                             code_is_provisional_identity=product['source_kind'] == 'UNKNOWN'
                             and product['external_id'] == product['code'],
                             active_fact_count=len(facts),
                             saved_roles=sorted({f['before'] for f in facts}),
                             current_salary_change_needed=any(
                                 f['before'] != row['name_based_salary'] for f in facts),
                             identity_approved=False, applied=False))
    result = dict(scope='REVIEW_ONLY', snapshot_sha256=sha(args.snapshot),
                  confirmations_sha256=sha(args.confirmations), analyzer_sha256=sha(Path(__file__)),
                  calculation_sha256=sha(Path(__file__).with_name('reconcile_snapshot.py')),
                  confirmed_zero_cost_lines=len(zero_cost), confirmed_zero_reward_lines=len(zero_price),
                  identity_exceptions=identity, monthly_shadow=impact, effective_from=None, applied=False,
                  scope_note='Legacy store-fund scenario, not new personal payroll or approved payouts; source costs untouched')
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2, default=str)
        stream.write('\n')
    print(json.dumps(dict(confirmed_zero_cost_lines=len(zero_cost),
                         confirmed_zero_reward_lines=len(zero_price), affected_months=len(impact))))


if __name__ == '__main__':
    main()

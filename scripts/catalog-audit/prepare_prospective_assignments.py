#!/usr/bin/env python3
"""Offline product-level rollout audit. No effective date, SQL or executable apply manifest."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
from datetime import date, datetime
import hashlib
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/payroll-audit'))
from reconcile_snapshot import load_snapshot


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def instant(value):
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.tzinfo is None:
        raise ValueError('Assignment timestamps must include a timezone')
    return result


def unique(rows, key):
    result = {}
    for row in rows:
        identity = key(row)
        if identity in result:
            raise ValueError('Duplicate source identity')
        result[identity] = row
    return result


def histories(rows, at, parse=instant):
    """Compare instants, not timestamp text; retain complete histories for later preflight."""
    grouped = defaultdict(list)
    for row in rows:
        grouped[row['product_id']].append(row)
    result = {}
    for product, entries in grouped.items():
        entries = sorted(entries, key=lambda r: parse(r['valid_from']))
        prior_end = None
        for index, row in enumerate(entries):
            start = parse(row['valid_from'])
            end = parse(row['valid_to']) if row.get('valid_to') else None
            if (end is not None and end <= start) or (index and (prior_end is None or start < prior_end)):
                raise ValueError('Invalid or overlapping assignment intervals')
            prior_end = end
        current = [r for r in entries if at is not None and parse(r['valid_from']) <= at
                   and (r.get('valid_to') is None or at < parse(r['valid_to']))]
        result[product] = (entries, current[0] if current else None)
    return result


def audit(tables, review, decisions, registry, snapshot_at):
    at = instant(snapshot_at)
    connection_key = review['summary']['connection_key']
    if (decisions.get('mode') != 'OWNER_CONFIRMED_NOT_APPLIED'
            or decisions.get('connection_key') != connection_key):
        raise ValueError('Owner journal mode/connection mismatch')
    reviewed = unique(review['records'], lambda r: (r['source_kind'], r['code']))
    approved = unique(decisions['decisions'], lambda r: (r['source_kind'], r['code']))
    for key, decision in approved.items():
        row = reviewed.get(key)
        if (row is None or row.get('owner_decision') != decision
                or row.get('owner_confirmed_category') != decision['category']
                or row['name'] != decision['expected_name']
                or row['source_group'] != decision['expected_group']):
            raise ValueError('Owner journal and review disagree')
    if any(r.get('owner_confirmed_category') and key not in approved for key, r in reviewed.items()):
        raise ValueError('Review confirmation missing from owner journal')
    connections = unique(tables['integration_connections'], lambda r: r['id'])
    products = unique(tables['products'], lambda r: r['id'])
    categories = unique(tables['analytics_categories'], lambda r: r['id'])
    by_code = unique(categories.values(), lambda r: r['code'])
    groups = unique(tables.get('source_product_groups', []), lambda r: r['id'])
    assignments = histories(tables['product_category_assignments'], at)
    # Payroll uses business dates. Preserve/validate intervals only; do not infer an effective payroll date.
    payroll = histories(tables.get('product_payroll_category_assignments', []), None, date.fromisoformat)
    identities = Counter((p['connection_id'], p['source_kind'], p.get('code')) for p in products.values())
    reviewed_codes = defaultdict(list)
    for row in reviewed.values():
        reviewed_codes[row['code']].append(row)
    documents = {r['id']: r for r in tables.get('sales_documents', [])}
    fact_counts = Counter(i['product_id'] for i in tables.get('sales_document_items', [])
                          if not i['is_deleted'] and not documents[i['sales_document_id']]['is_deleted'])
    present = set()
    present_codes = set()
    records = []
    for product in products.values():
        key = product['source_kind'], product.get('code')
        row = reviewed.get(key)
        correct_connection = connections[product['connection_id']]['connection_key'] == connection_key
        if correct_connection:
            present.add(key)
            present_codes.add(product.get('code'))
        possible = reviewed_codes.get(product.get('code'), []) if correct_connection else []
        hint = possible[0] if row is None and len(possible) == 1 else None
        reasons = []
        if not correct_connection:
            reasons.append('CONNECTION_MISMATCH')
        if not product.get('external_id') or not product.get('code'):
            reasons.append('MISSING_PROVIDER_IDENTITY')
        if identities[(product['connection_id'], *key)] != 1:
            reasons.append('AMBIGUOUS_CATALOG_IDENTITY')
        if row is None:
            reasons.append('SOURCE_KIND_UNVERIFIED' if hint and product['source_kind'] == 'UNKNOWN'
                           else 'SOURCE_KIND_CONFLICT' if hint else 'NO_REVIEWED_IDENTITY')
        elif row['name'] != product['name']:
            reasons.append('PRODUCT_OBSERVATION_CHANGED')
        matched = not reasons
        target = (row.get('owner_confirmed_category') or row.get('candidate_category')) if matched else None
        exact = matched and key in approved
        original_target = target
        release_policy = None
        # Later owner decision: keep analytical service unsplit. Never changes payroll classification.
        if product['source_kind'] == 'SERVICE' and target in {'REPAIR_SERVICE', 'DIAGNOSTICS_SERVICE'}:
            target = 'SETUP_SERVICE'
            release_policy = 'OWNER_SERVICE_UNSPLIT'
        if target and (target not in registry or registry[target]['scope'] == 'DEFERRED'):
            reasons.append('TARGET_NOT_IN_RELEASE')
            target = None
        confirmed_target = target if exact else None
        if target and not exact:
            reasons.append('DECISION_EVIDENCE_REQUIRED')
        if not product.get('is_active', False):
            reasons.append('PRODUCT_INACTIVE')
        group = groups.get(product.get('source_group_id'))
        group_status = 'NOT_OBSERVED'
        if group and row:
            group_status = 'MATCH' if group.get('path') == row['source_group'] else 'DIFFERS'
            if group_status == 'DIFFERS':
                reasons.append('SOURCE_GROUP_CHANGED')
        history, current = assignments.get(product['id'], ([], None))
        current_code = categories[current['analytics_category_id']]['code'] if current else None
        if target:
            action = ('ALREADY_ASSIGNED' if current_code == target else
                      'REQUIRES_DATED_REPLACEMENT' if current else 'REQUIRES_DATED_ASSIGNMENT')
            if target not in by_code:
                reasons.append('TARGET_NOT_IN_SNAPSHOT')
            elif not by_code[target]['is_active']:
                reasons.append('TARGET_INACTIVE')
        else:
            action = 'NO_VALIDATED_TARGET'
        if any(instant(h['valid_from']) > at for h in history):
            reasons.append('FUTURE_ASSIGNMENT_EXISTS')
        records.append(dict(
            product_id=product['id'], external_id=product.get('external_id'),
            connection_id=product['connection_id'], source_kind=product['source_kind'],
            code=product.get('code'), name=product['name'], expected_product_version=product.get('version'),
            exact_review_match=matched, candidate_category=target, confirmed_category=confirmed_target,
            active_fact_count=fact_counts[product['id']],
            possible_review_identity=(dict(source_kind=hint['source_kind'], code=hint['code'],
                                           name_matches=hint['name'] == product['name'],
                                           has_owner_decision=(hint['source_kind'], hint['code']) in approved)
                                      if hint else None),
            decision_source='EXACT_OWNER_JOURNAL' if exact else 'PROPOSAL_OR_UNMATCHED',
            original_candidate_category=original_target, release_policy=release_policy,
            owner_decision=approved.get(key) if exact else None,
            current_assignment_category=current_code, assignment_history=history,
            payroll_assignment_history=payroll.get(product['id'], ([], None))[0],
            requires_payroll_preflight=bool(target and current_code != target),
            source_group_verification=group_status,
            remaining_review_reasons=row.get('reasons', []) if matched else [],
            candidate_action=action, reasons=sorted(reasons), apply_allowed=False,
        ))
    records.sort(key=lambda r: (r['connection_id'], r['source_kind'], r['code'] or '', r['product_id']))
    owner_status = Counter()
    for key in approved:
        matches = [r for r in records if connections[r['connection_id']]['connection_key'] == connection_key
                   and r['code'] == key[1]]
        if not matches:
            owner_status['NOT_IN_SNAPSHOT'] += 1
        elif len(matches) != 1:
            owner_status['AMBIGUOUS_IDENTITY'] += 1
        elif matches[0]['source_kind'] != key[0]:
            owner_status['SOURCE_KIND_UNVERIFIED_OR_CONFLICT'] += 1
        elif matches[0]['name'] != approved[key]['expected_name']:
            owner_status['PRODUCT_OBSERVATION_CHANGED'] += 1
        elif matches[0]['confirmed_category']:
            owner_status['EXACT_OWNER_CATEGORY'] += 1
        else:
            owner_status['TARGET_NOT_IN_RELEASE' if 'TARGET_NOT_IN_RELEASE' in matches[0]['reasons']
                         else 'OTHER_GUARD'] += 1
    source_only = [dict(source_kind=kind, code=code) for kind, code in sorted(reviewed)
                   if code not in present_codes]
    return dict(summary=dict(
        contract='prospective-catalog-audit-v1', scope='REVIEW_ONLY_NOT_APPLY_MANIFEST',
        effective_from=None, snapshot_at=snapshot_at, product_count=len(records),
        reviewed_source_count=len(reviewed), journal_decisions=len(approved),
        exact_review_matches=sum(r['exact_review_match'] for r in records),
        exact_owner_categories=sum(r['confirmed_category'] is not None for r in records),
        source_only_count=len(source_only),
        missing_exact_review_identities=len(set(reviewed) - present),
        owner_decision_reconciliation=dict(owner_status),
        actions=dict(Counter(r['candidate_action'] for r in records)),
        reasons=dict(Counter(reason for r in records for reason in r['reasons'])),
        group_verification=dict(Counter(r['source_group_verification'] for r in records)),
        assignments_written=0, historical_facts_written=0, payroll_changes_applied=0,
        payroll_preflight_completed=False, release_ready=False,
    ), records=records,
        source_only=source_only)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('snapshot', 'review', 'decisions', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    tables, first, _ = load_snapshot(args.snapshot)
    review = json.loads(args.review.read_text())
    decisions = json.loads(args.decisions.read_text())
    registry_path = ROOT / 'backend/src/main/resources/catalog/category-registry-v2.tsv'
    if (review['summary'].get('category_registry_sha256') != digest(registry_path)
            or review['summary'].get('owner_decisions_sha256') != digest(args.decisions)):
        raise ValueError('Review registry or owner journal checksum mismatch')
    with registry_path.open() as source:
        next(source)
        registry = {r['code']: r for r in csv.DictReader(source, delimiter='\t')}
    result = audit(tables, review, decisions, registry, first['snapshot_at'])
    result['summary']['input_sha256'] = {name: digest(path) for name, path in (
        ('snapshot', args.snapshot), ('review', args.review), ('decisions', args.decisions),
        ('registry', registry_path))}
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    (args.output / 'prospective-audit.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result['summary'], ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()

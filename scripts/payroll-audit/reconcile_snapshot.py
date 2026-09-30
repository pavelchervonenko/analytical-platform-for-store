#!/usr/bin/env python3
"""Offline catalog reconciliation and legacy-payroll shadow. Never applies assignments."""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/catalog-audit'))
from build_review import write_xlsx
from lexicon_audit import Lexicon
from build_catalog_mapping import proposal

RESOLVER_SHA = 'f8cd619093e29e8bf452ee075debda8b5254c7ac3700c51b9bfb495c76ed8a8f'
MONEY = Decimal('.01')
ZERO = Decimal(0)
ROLES = {'TECH_TIER_1', 'TECH_TIER_2', 'ACCESSORY', 'SERVICE',
         'PLAYSTATION_SUBSCRIPTION', 'PAID_REPAIR', 'EXCLUDE', 'UNMAPPED'}


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def normalized(value):
    return ' '.join(value.casefold().replace('ё', 'е').split())


def fallback(category, name, base):
    """Exact semantics of the pinned, observed server resolver, not the local candidate."""
    name = (name or '').lower()
    if category == 'IPAD_MAC':
        if 'macbook' in name:
            return 'TECH_TIER_1'
        if 'ipad' in name or any(w in name for w in ('apple pencil', 'magic mouse', 'magic keyboard')):
            return 'TECH_TIER_2'
    if category in {'IPAD_MAC', 'PODS_WATCH_OTHER_DEVICE'} and 'dyson' in name:
        return 'TECH_TIER_1'
    if category == 'PODS_WATCH_OTHER_DEVICE' and (
            re.search(r'playstation\s*5', name) or re.search(r'(^|[\W_])ps\s*5([\W_]|$)', name)):
        return 'TECH_TIER_1'
    return base


def dated_assignment(rows, at):
    if at is None:
        return None
    found = [r for r in rows if r['valid_from'] <= at and
             (r['valid_to'] is None or at < r['valid_to'])]
    if len(found) > 1:
        raise ValueError('Overlapping assignment intervals; manual review required')
    return found[0] if found else None


def money(value):
    return value.quantize(MONEY, rounding=ROUND_HALF_UP)


def load_snapshot(path):
    checksum = path.with_name('snapshot.sha256').read_text().split()
    if len(checksum) != 2 or checksum[1] != 'snapshot.jsonl' or sha(path) != checksum[0]:
        raise ValueError('Snapshot checksum mismatch')
    rows = [json.loads(line, parse_float=Decimal) for line in path.open()]
    first, last = rows[0], rows[-1]
    if (first['kind'] != 'snapshot_start' or last['kind'] != 'snapshot_end'
            or first['snapshot_at'] != last['snapshot_at'] or first['role_guard'] != 1
            or first['isolation'] != 'repeatable read'
            or any(r['read_only'] != 'on' for r in (first, last))):
        raise ValueError('Invalid snapshot boundary')
    counts = Counter(r['kind'] for r in rows)
    manifests = [r for r in rows if r['kind'] == 'table_manifest']
    if len(manifests) != 20 or len({r['table'] for r in manifests}) != 20:
        raise ValueError('Invalid table manifests')
    tables = defaultdict(list)
    for row in rows:
        if 'data' in row:
            tables[row['kind']].append(row['data'])
    for manifest in manifests:
        name = manifest['table']
        if manifest['row_count'] != counts[name]:
            raise ValueError('Truncated table: ' + name)
        if len({r['id'] for r in tables[name]}) != len(tables[name]):
            raise ValueError('Duplicate identity: ' + name)
    resolvers = [r for r in rows if r['kind'] == 'payroll_resolver']
    if len(resolvers) != 1 or hashlib.sha256(resolvers[0]['definition'].encode()).hexdigest() != RESOLVER_SHA:
        raise ValueError('Unknown server resolver; inspect before evaluating')
    return tables, first, last


def shadow_month(facts, field, scheme, plan, zero_price_repair_ids=()):
    # Optional D-019 what-if only. Default reproduces the observed legacy baseline.
    zero_price_repair_ids = set(zero_price_repair_ids)
    confirmed = [f for f in facts if f.get('item_id') in zero_price_repair_ids]
    if len(confirmed) != len(zero_price_repair_ids) or any(
            f[field] != 'PAID_REPAIR' or f['net_amount'] != ZERO
            or f['sign'] != 1 or f['analytically_excluded'] for f in confirmed):
        raise ValueError('Zero-price repair scope does not match eligible sale facts')
    days = defaultdict(lambda: defaultdict(lambda: ZERO))
    for f in facts:
        role = f[field]
        if f['analytically_excluded']:
            continue
        d = days[f['business_date']]
        sign = f['sign']
        d['revenue'] += sign * f['net_amount']
        if role in {'ACCESSORY', 'SERVICE'}:
            d[role] += sign * f['net_amount']
        elif role in {'TECH_TIER_1', 'TECH_TIER_2'}:
            d[role] += sign * f['quantity']
        elif role in {'PAID_REPAIR', 'PLAYSTATION_SUBSCRIPTION'}:
            if f.get('item_id') in zero_price_repair_ids:
                d[role] += ZERO
            elif f['cost_amount'] is None:
                d['missing_cost'] += 1
            else:
                d[role] += sign * (f['net_amount'] - f['cost_amount'])
        elif role == 'UNMAPPED' or role is None:
            d['unmapped'] += 1
        elif role != 'EXCLUDE':
            raise ValueError('Unexpected salary role')
    totals = {k: sum((d[k] for d in days.values()), ZERO) for k in
              ['revenue', 'ACCESSORY', 'SERVICE', 'TECH_TIER_1', 'TECH_TIER_2',
               'PAID_REPAIR', 'PLAYSTATION_SUBSCRIPTION', 'unmapped', 'missing_cost']}
    result = dict(totals)
    result['days'] = len(days)
    result['fund'] = None
    result['complete_days_fund'] = None
    result['incomplete_days'] = sum(bool(d['unmapped'] or d['missing_cost']) for d in days.values())
    if plan is None or scheme is None:
        result['status'] = 'NO_PLAN_OR_SCHEME'
        return result
    revenue_ok = totals['revenue'] >= plan['revenue_target']
    acc_ok = totals['revenue'] > 0 and totals['ACCESSORY'] * 100 >= plan['accessory_share_target'] * totals['revenue']
    svc_ok = totals['revenue'] > 0 and totals['SERVICE'] * 100 >= plan['service_share_target'] * totals['revenue']
    ar = scheme['achieved_percentage' if acc_ok else 'missed_percentage']
    sr = scheme['achieved_percentage' if svc_ok else 'missed_percentage']
    t1 = scheme['achieved_tier1_rate' if revenue_ok else 'missed_tier1_rate']
    t2 = scheme['achieved_tier2_rate' if revenue_ok else 'missed_tier2_rate']
    result.update(revenue_achieved=revenue_ok, accessory_achieved=acc_ok, service_achieved=svc_ok,
                  accessory_rate=ar, service_rate=sr, tier1_rate=t1, tier2_rate=t2)
    complete_sum = ZERO
    for d in days.values():
        if d['unmapped'] or d['missing_cost']:
            continue
        complete_sum += (money(d['ACCESSORY'] * ar / 100) + money(d['SERVICE'] * sr / 100)
                         + money(d['PAID_REPAIR'] * sr / 100)
                         + money(d['PLAYSTATION_SUBSCRIPTION'] * sr / 100)
                         + money(d['TECH_TIER_1'] * t1) + money(d['TECH_TIER_2'] * t2))
    result['complete_days_fund'] = complete_sum
    if not result['incomplete_days']:
        result['fund'] = complete_sum
    result['status'] = 'INCOMPLETE_CLASSIFICATION_OR_COST' if result['incomplete_days'] else 'FUND_ONLY_SHIFT_ALLOCATION_SEPARATE'
    return result


def audit(snapshot, mapping):
    t, start, end = load_snapshot(snapshot)
    reviewed = json.loads(mapping.read_text())['records']
    by_code = {r['code']: r for r in reviewed}
    products = {r['id']: r for r in t['products']}
    cats = {r['id']: r for r in t['analytics_categories']}
    docs = {r['id']: r for r in t['sales_documents']}
    items = {r['id']: r for r in t['sales_document_items']}
    stores = {r['id']: r['name'] for r in t['stores']}
    if len(by_code) != len(reviewed) or len({r['code'] for r in products.values()}) != len(products):
        raise ValueError('Code ambiguity: use connection + kind + provider ID review')
    if len({r['connection_id'] for r in products.values()}) != 1:
        raise ValueError('Multiple catalog scopes: review connection mapping first')
    salary_history, analytics_history = defaultdict(list), defaultdict(list)
    for a in t['product_payroll_category_assignments']:
        salary_history[a['product_id']].append(a)
    for a in t['product_category_assignments']:
        analytics_history[a['product_id']].append(a)
    now = datetime.fromisoformat(start['snapshot_at'])
    matches, missing_db, new_db, changed_names = {}, [], [], []
    current = []
    lexicon_path = ROOT / 'scripts/catalog-audit/catalog_lexicon.json'
    lexicon = Lexicon(json.loads(lexicon_path.read_text()), t['integration_connections'][0]['connection_key'])
    sold = Counter(i['product_id'] for i in items.values()
                   if not i['is_deleted'] and not docs[i['sales_document_id']]['is_deleted'])
    for p in products.values():
        r = by_code.get(p['code'])
        identity = 'ABSENT_FROM_EXCEL'
        if r:
            identity = ('EXACT_CODE_NAME' if normalized(p['name']) == normalized(r['name']) else 'NAME_CHANGED')
            if p['source_kind'] not in {r['source_kind'], 'UNKNOWN'}:
                identity = 'SOURCE_KIND_CONFLICT'
            if identity == 'EXACT_CODE_NAME':
                matches[p['id']] = r
            else:
                changed_names.append(dict(p, excel_name=r['name'], proposed_payroll=r['proposed_payroll'], identity=identity))
        guess = {}
        if not r or identity != 'EXACT_CODE_NAME':
            if p['source_kind'] in {'PRODUCT', 'SERVICE'}:
                proposed = lexicon.propose(p['name'], p['source_kind'])
                guess = proposal(dict(name=p['name'], role=proposed['role'], review_types=proposed['types']))
                guess.update(analytical_proposal=proposed['candidate_category'], lexicon_reasons=' | '.join(proposed['reasons']))
            else:
                guess = dict(proposed_payroll='UNMAPPED', reason='Source kind UNKNOWN; identity review required')
            if not r:
                new_db.append(dict(p, **guess, active_sale_lines=sold[p['id']], approved=False))
        active = [a for a in analytics_history[p['id']]
                  if datetime.fromisoformat(a['valid_from']) <= now
                  and (a['valid_to'] is None or now < datetime.fromisoformat(a['valid_to']))]
        if len(active) > 1:
            raise ValueError('Multiple active analytical assignments')
        c = cats[active[0]['analytics_category_id']] if active else None
        override = dated_assignment(salary_history[p['id']], now.date().isoformat())
        effective = override['payroll_category_code'] if override else (fallback(c['code'], p['name'], c['payroll_category_code']) if c else None)
        current.append(dict(product_id=p['id'], external_id=p['external_id'], connection_id=p['connection_id'],
                            code=p['code'], name=p['name'], source_kind=p['source_kind'], identity=identity,
                            current_analytics=c['code'] if c else 'NO_ASSIGNMENT',
                            current_payroll=effective or 'NO_ASSIGNMENT',
                            payroll_origin='MANUAL' if override else 'ANALYTICAL_FALLBACK' if c else 'NO_ASSIGNMENT',
                            proposed_payroll=r['proposed_payroll'] if r else guess.get('proposed_payroll'),
                            active_sale_lines=sold[p['id']], effective_from='', applied=False))
    db_codes = {p['code'] for p in products.values()}
    missing_db = [dict(r, identity='ABSENT_FROM_DATABASE') for r in reviewed if r['code'] not in db_codes]
    facts, links = [], []
    for i in items.values():
        d = docs[i['sales_document_id']]
        if i['is_deleted'] or d['is_deleted']:
            continue
        p, c = products[i['product_id']], cats[i['analytics_category_id']]
        orig = docs.get(d['original_document_id'])
        at = d['business_date'] if d['document_kind'] == 'SALE' else (orig['business_date'] if orig else None)
        override = dated_assignment(salary_history[p['id']], at)
        before = override['payroll_category_code'] if override else fallback(c['code'], p['name'], c['payroll_category_code'])
        r = matches.get(p['id'])
        after = r['proposed_payroll'] if r else before
        if after not in ROLES or before not in ROLES:
            raise ValueError('Unexpected role in facts')
        fact = dict(item_id=i['id'], document_id=d['id'], product_id=p['id'], code=p['code'], name=p['name'],
                    store_id=d['store_id'], store=stores[d['store_id']], business_date=d['business_date'],
                    document_kind=d['document_kind'], original_document_id=d['original_document_id'],
                    original_item_id=i['original_item_id'], classification_date=at,
                    sign=1 if d['document_kind'] == 'SALE' else -1,
                    quantity=i['quantity'], net_amount=i['net_amount'], cost_amount=i['cost_amount'],
                    cost_quality=i['cost_quality'], is_work=i['is_work'], employee_id=d['employee_id'],
                    analytics=c['code'], analytically_excluded=c['code']=='EXCLUDE',
                    before=before, after=after, reviewed_match=bool(r),
                    override_id=override['id'] if override else None,
                    changed=before != after and c['code'] != 'EXCLUDE')
        facts.append(fact)
        if d['document_kind'] == 'RETURN':
            oi = items.get(i['original_item_id'])
            issues = []
            if orig is None or orig['is_deleted'] or orig['document_kind'] != 'SALE':
                issues.append('INVALID_ORIGINAL_DOCUMENT')
            if oi is None or oi['is_deleted'] or oi['sales_document_id'] != d['original_document_id'] or oi['product_id'] != i['product_id']:
                issues.append('INVALID_ORIGINAL_ITEM')
            if issues:
                links.append(dict(item_id=i['id'], code=p['code'], store=stores[d['store_id']],
                                  business_date=d['business_date'], original_document_id=d['original_document_id'],
                                  original_item_id=i['original_item_id'], issues=issues))
    changed = [f for f in facts if f['changed']]
    grouping = defaultdict(list)
    for f in facts:
        grouping[(f['store_id'], f['business_date'][:7])].append(f)
    plans = {(p['store_id'], p['plan_month'][:7]): p for p in t['store_performance_plans']}
    monthly = []
    for (store, month), fs in sorted(grouping.items()):
        schemes = [s for s in t['payroll_schemes'] if s['effective_from'] <= month+'-01']
        scheme = max(schemes, key=lambda s:s['effective_from']) if schemes else None
        baseline = shadow_month(fs, 'before', scheme, plans.get((store,month)))
        candidate = shadow_month(fs, 'after', scheme, plans.get((store,month)))
        active_shifts = [s for s in t['employee_work_shifts'] if s['store_id']==store and s['is_active'] and s['work_date'].startswith(month)]
        days_with_shifts = {s['work_date'] for s in active_shifts}
        rows_changed = [f for f in fs if f['changed']]
        monthly.append(dict(store=stores[store], store_id=store, month=month,
                            active_lines=len(fs), reviewed_lines=sum(f['reviewed_match'] for f in fs),
                            changed_lines=len(rows_changed), changed_products=len({f['product_id'] for f in rows_changed}),
                            active_shifts=len(active_shifts), sales_days_without_shift=len({f['business_date'] for f in fs}-days_with_shifts),
                            baseline=baseline, candidate=candidate,
                            fund_delta=(candidate['fund']-baseline['fund'] if baseline['fund'] is not None and candidate['fund'] is not None else None)))
    transitions = []
    transition_groups = defaultdict(list)
    for f in changed:
        transition_groups[(f['before'],f['after'])].append(f)
    for (before,after),fs in sorted(transition_groups.items()):
        transitions.append(dict(before=before,after=after,lines=len(fs),products=len({f['product_id'] for f in fs}),
                                signed_quantity=sum((f['sign']*f['quantity'] for f in fs),ZERO),
                                signed_net=sum((f['sign']*f['net_amount'] for f in fs),ZERO)))
    paid_costs=[f for f in facts if f['after'] in {'PAID_REPAIR','PLAYSTATION_SUBSCRIPTION'} and f['reviewed_match']]
    summary = dict(contract='payroll-reconciliation-v1', scope='OFFLINE_HISTORICAL_SHADOW_EXACT_MATCHES_ONLY',
                   snapshot_at=start['snapshot_at'], snapshot_sha256=sha(snapshot), mapping_sha256=sha(mapping),
                   resolver_sha256=RESOLVER_SHA, lexicon_sha256=sha(lexicon_path), analyzer_sha256=sha(__file__),
                   first_business_date=end['first_business_date'], last_business_date=end['last_business_date'],
                   database_products=len(products), excel_rows=len(reviewed), exact_code_name_matches=len(matches),
                   matched_unknown_source_kind=sum(products[pid]['source_kind']=='UNKNOWN' for pid in matches),
                   changed_names=len(changed_names), database_only=len(new_db), excel_only=len(missing_db),
                   active_sale_lines=len(facts), reviewed_sale_lines=sum(f['reviewed_match'] for f in facts),
                   changed_sale_lines=len(changed), changed_sold_products=len({f['product_id'] for f in changed}),
                   invalid_return_links=len(links), payroll_assignment_rows=len(t['product_payroll_category_assignments']),
                   saved_runs=len(t['payroll_runs']), approved_or_paid_runs=sum(r['status'] in {'APPROVED','PAID'} for r in t['payroll_runs']),
                   proposed_gp_lines=len(paid_costs), gp_cost_qualities=dict(Counter(f['cost_quality'] for f in paid_costs)),
                   gp_zero_cost_lines=sum(f['cost_amount']==0 for f in paid_costs), gp_missing_cost_lines=sum(f['cost_amount'] is None for f in paid_costs),
                   unreviewed_proposal_roles=dict(Counter(r['proposed_payroll'] for r in new_db)),
                   data_applied=False, effective_from=None)
    return dict(summary=summary, catalog=current, database_only=new_db, excel_only=missing_db,
                changed_names=changed_names, changed_facts=changed, facts=facts, months=monthly,
                transitions=transitions, return_issues=links, salary_assignments=t['product_payroll_category_assignments'],
                saved_runs=t['payroll_runs'], saved_pools=t['payroll_daily_pools'],
                saved_statements=t['payroll_statements'])


def sheet(rows, columns):
    return [columns]+[[r.get(k,'') for k in columns] for r in rows]


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--mapping', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args=parser.parse_args()
    result=audit(args.snapshot,args.mapping)
    os.umask(0o077)
    args.output.mkdir(mode=0o700,parents=True,exist_ok=False)
    (args.output/'reconciliation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2,default=str)+'\n')
    (args.output/'summary.json').write_text(json.dumps(result['summary'],ensure_ascii=False,indent=2,default=str)+'\n')
    months=[]
    for m in result['months']:
        row={k:v for k,v in m.items() if k not in {'baseline','candidate'}}
        for side in ['baseline','candidate']:
            row.update({side+'_'+k:v for k,v in m[side].items()})
        months.append(row)
    sheets=[('Summary', [['Параметр','Значение']]+[[k,str(v)] for k,v in result['summary'].items()]),
            ('Catalog',sheet(result['catalog'],list(result['catalog'][0]))),
            ('Changed sale roles',sheet(result['changed_facts'],['store','business_date','code','name','before','after','document_kind','quantity','net_amount','cost_amount','cost_quality','product_id','item_id','override_id'])),
            ('Monthly shadow',sheet(months,list(months[0]))),
            ('Transitions',sheet(result['transitions'],['before','after','products','lines','signed_quantity','signed_net'])),
            ('DB only proposals',sheet(result['database_only'],['code','name','source_kind','proposed_payroll','reason','analytical_proposal','lexicon_reasons','active_sale_lines','id','external_id','approved'])),
            ('Excel only',sheet(result['excel_only'],['code','name','source_kind','proposed_payroll','analytical_candidate','owner_decision_id','identity'])),
            ('Changed names',sheet(result['changed_names'],['code','name','excel_name','source_kind','proposed_payroll','identity','id','external_id'])),
            ('Return links review',sheet(result['return_issues'],['store','business_date','code','item_id','original_document_id','original_item_id','issues'])),
            ('Repair cost review',sheet([f for f in result['facts'] if f['reviewed_match'] and f['after'] in {'PAID_REPAIR','PLAYSTATION_SUBSCRIPTION'} and (f['cost_amount'] is None or f['cost_amount']==0)],['store','business_date','code','name','after','net_amount','cost_amount','cost_quality','item_id'])),
            ('Salary history',sheet(result['salary_assignments'],['product_id','payroll_category_code','valid_from','valid_to','id'])),
            ('Saved runs',sheet(result['saved_runs'],list(result['saved_runs'][0]) if result['saved_runs'] else ['id','status']))]
    write_xlsx(args.output/'payroll-reconciliation.xlsx',sheets)
    print(json.dumps(result['summary'],ensure_ascii=False,indent=2,default=str))


if __name__=='__main__':
    main()

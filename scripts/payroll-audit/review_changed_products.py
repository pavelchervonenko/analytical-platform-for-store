#!/usr/bin/env python3
"""Build a non-executable review of salary roles that affect observed sale rows."""
import argparse
from collections import Counter, defaultdict
from decimal import Decimal
import json
import os
import re
from pathlib import Path
from reconcile_snapshot import ROOT, load_snapshot, sha, shadow_month
from build_review import write_xlsx
from lexicon_audit import Lexicon
from build_catalog_mapping import proposal


def group_for(row):
    target, types = row['proposed_payroll'], row['observed_types'].split(' | ')
    if target == 'ACCESSORY' and types == ['GAMING_ACCESSORY']:
        return 'DUALSENSE_ACCESSORY'
    if target == 'ACCESSORY':
        return 'APPLE_INPUT_ACCESSORY'
    if target == 'TECH_TIER_2' and types == ['SAMSUNG_WATCH']:
        return 'SAMSUNG_WATCH_TIER2'
    if target == 'PAID_REPAIR':
        return 'REPAIR_GROSS_PROFIT'
    return 'SERVICE_TURNOVER' if target == 'SERVICE' else 'OTHER_DEVICE_TIER2'


def table(rows, keys):
    return [keys] + [[r.get(k, '') for k in keys] for r in rows]


def build(snapshot_path, mapping_path, reconciliation_path):
    tables, start, _ = load_snapshot(snapshot_path)
    rec = json.loads(reconciliation_path.read_text())
    if (rec['summary']['snapshot_sha256'] != sha(snapshot_path)
            or rec['summary']['mapping_sha256'] != sha(mapping_path)):
        raise ValueError('Review inputs do not refer to the same snapshot/catalog')
    mapping = {r['code']: r for r in json.loads(mapping_path.read_text())['records']}
    products = {p['id']: p for p in tables['products']}
    facts = rec['facts']
    for f in facts:
        for key in ['net_amount', 'cost_amount', 'quantity']:
            f[key] = Decimal(str(f[key])) if f[key] is not None else None
    changed_ids = {f['product_id'] for f in facts if f['changed']}
    rows, history, costs, negative = [], [], [], []
    group_ids = defaultdict(set)
    for pid in sorted(changed_ids, key=lambda pid: int(products[pid]['code'])):
        p = products[pid]
        m = mapping[p['code']]
        fs = [f for f in facts if f['product_id'] == pid]
        changed = [f for f in fs if f['changed']]
        if not all(f['reviewed_match'] and f['after'] == m['proposed_payroll'] for f in fs):
            raise ValueError('Salary target or identity mismatch')
        group = group_for(m)
        group_ids[group].add(pid)
        assignments = [a for a in tables['product_payroll_category_assignments'] if a['product_id'] == pid]
        for a in assignments:
            history.append(dict(code=p['code'], name=p['name'], **a,
                                covered_lines=sum(f['override_id'] == a['id'] for f in fs)))
        cost_review = []
        if m['proposed_payroll'] in {'PAID_REPAIR', 'PLAYSTATION_SUBSCRIPTION'}:
            for f in fs:
                if f['cost_amount'] is None or f['cost_amount'] == 0:
                    cost_review.append(f['item_id'])
                    costs.append(dict(f, reason='FULL_COST_REQUIRES_CONFIRMATION'))
                if f['document_kind'] == 'SALE' and f['cost_amount'] is not None and f['net_amount'] < f['cost_amount']:
                    negative.append(dict(f, gross_profit=f['net_amount']-f['cost_amount'],
                                         reason='VERIFY_TRANSACTION_PURPOSE_AND_AMOUNTS; DO_NOT_CLAMP_PROFIT'))
        rows.append(dict(group=group, code=p['code'], name=p['name'], source_kind=p['source_kind'],
                         product_id=pid, provider_id=p['external_id'], connection_id=p['connection_id'],
                         observed_product_version=p['version'], observed_product_updated_at=p['updated_at'],
                         observed_salary_roles=' | '.join(sorted({f['before'] for f in fs})),
                         observed_sale_analytics=' | '.join(sorted({f['analytics'] for f in fs})),
                         target_salary=m['proposed_payroll'], target_analytics=m['analytical_display'],
                         basis=m['basis'], reason=m['reason'], evidence_types=m['observed_types'],
                         proposal_status=m['proposal_status'], owner_decision_id=m['owner_decision_id'],
                         conclusion='CONSISTENT_WITH_ACCEPTED_RULES', changed_lines=len(changed),
                         all_sale_lines=len(fs), already_correct_lines=len(fs)-len(changed),
                         first_changed_date=min(f['business_date'] for f in changed),
                         last_changed_date=max(f['business_date'] for f in changed),
                         existing_assignments=len(assignments), cost_review_lines=len(cost_review),
                         effective_from=None, historical_correction_approved=False, applied=False,
                         application_gate='DATE_AND_HISTORY_SCOPE_PENDING; REVALIDATE_LIVE_VERSIONS'))
    by_month = defaultdict(list)
    for f in facts:
        by_month[(f['store_id'], f['business_date'][:7])].append(f)
    plans = {(p['store_id'], p['plan_month'][:7]):p for p in tables['store_performance_plans']}
    stores = {s['id']:s['name'] for s in tables['stores']}
    impact = []
    for (sid, month), fs in sorted(by_month.items()):
        plan = plans.get((sid, month))
        schemes = [s for s in tables['payroll_schemes'] if s['effective_from'] <= month+'-01']
        scheme = max(schemes, key=lambda s:s['effective_from']) if schemes else None
        baseline = shadow_month(fs, 'before', scheme, plan)
        for group, ids in sorted(group_ids.items()):
            changes = [f for f in fs if f['product_id'] in ids and f['changed']]
            if not changes:
                continue
            scenario = [dict(f, proposed=f['after'] if f['product_id'] in ids else f['before']) for f in fs]
            after = shadow_month(scenario, 'proposed', scheme, plan)
            rates_changed = (any(baseline.get(k) != after.get(k) for k in
                                ['accessory_rate','service_rate','tier1_rate','tier2_rate']) if plan else None)
            impact.append(dict(store=stores[sid], month=month, group=group, changed_lines=len(changes),
                               baseline_fund=baseline['fund'], scenario_fund=after['fund'],
                               fund_delta=after['fund']-baseline['fund'] if after['fund'] is not None and baseline['fund'] is not None else None,
                               rates_changed=rates_changed, baseline_status=baseline['status'],
                               scenario_status=after['status'], amount_scope='LEGACY_STORE_FUND_NOT_EMPLOYEE_PAYOUT'))
    # Additivity is checked only for complete months and unchanged rates.
    for month in rec['months']:
        checks = [i for i in impact if i['store']==month['store'] and i['month']==month['month']]
        if month['fund_delta'] is not None and all(i['fund_delta'] is not None and not i['rates_changed'] for i in checks):
            if sum((i['fund_delta'] for i in checks), Decimal(0)) != Decimal(month['fund_delta']):
                raise ValueError('Group deltas do not reconcile to the complete monthly scenario')
    groups = [dict(group=g, products=len(ids), changed_lines=sum(r['changed_lines'] for r in rows if r['group']==g))
              for g,ids in sorted(group_ids.items())]
    lexicon_path = ROOT / 'scripts/catalog-audit/catalog_lexicon.json'
    lexicon = Lexicon(json.loads(lexicon_path.read_text()), tables['integration_connections'][0]['connection_key'])
    renamed = []
    def unit_markers(name):
        return sorted(token for token in re.findall(r'\b[A-Za-z0-9]{8,}\b', name.upper())
                      if re.search('[A-Z]', token) and re.search('[0-9]', token))
    for changed_name in rec['changed_names']:
        old = mapping[changed_name['code']]
        # The known Excel type/group is context for a salary recommendation, not a new DB identity.
        extracted = lexicon.propose(changed_name['name'], old['source_kind'], old['source_group'])
        new = proposal(dict(name=changed_name['name'], role=extracted['role'], review_types=extracted['types']))
        marker_changed = unit_markers(changed_name['name']) != unit_markers(old['name'])
        renamed.append(dict(code=changed_name['code'], product_id=changed_name['id'],
                            excel_name=old['name'], database_name=changed_name['name'],
                            old_salary=old['proposed_payroll'], name_based_salary=new['proposed_payroll'],
                            salary_unchanged=old['proposed_payroll']==new['proposed_payroll'],
                            unit_marker_changed=marker_changed,
                            identity_review='UNIT_MARKER_REVIEW' if marker_changed else 'NAME_CHANGE_REVIEW',
                            identity_approved=False, applied=False))
    devices = []
    device_specs = [
        ('6287', 'POCO F8 Ultra ', 'TECH_TIER_1', None,
         'OTHER_PHONE_CATEGORY_MISSING', 'https://www.mi.com/uk/product/poco-f8-ultra/'),
        ('6409', 'Garmin Forerunner 970 ', 'TECH_TIER_2', 'WATCH_OTHER',
         'WATCH_NOT_FITNESS_BAND', 'https://www.garmin.com/en-US/p/1462801/'),
    ]
    for code, prefix, salary, analytical, note, url in device_specs:
        candidates = [p for p in products.values() if p['code']==code]
        if len(candidates)!=1 or not candidates[0]['name'].startswith(prefix):
            raise ValueError('Supplementary device evidence no longer matches the snapshot')
        p = candidates[0]
        fs = [f for f in facts if f['product_id']==p['id']]
        if not fs or any(f['is_work'] or f['before']!='UNMAPPED' for f in fs):
            raise ValueError('Re-review supplementary device facts')
        devices.append(dict(code=code, name=p['name'], product_id=p['id'], provider_id=p['external_id'],
                            source_kind=p['source_kind'], target_salary=salary, target_analytics=analytical,
                            evidence_url=url, evidence_note=note, sale_lines=len(fs),
                            payroll_basis='Количество единиц', effective_from=None, applied=False,
                            status='RULE_SUPPORTED_PROPOSAL_WITH_MANUFACTURER_TYPE_EVIDENCE'))
    summary = dict(contract='changed-payroll-products-review-v1', mode='REVIEW_ONLY_NO_IMPORT',
                   snapshot_at=start['snapshot_at'], snapshot_sha256=sha(snapshot_path), mapping_sha256=sha(mapping_path),
                   reconciliation_sha256=sha(reconciliation_path), script_sha256=sha(__file__),
                   reviewed_products=len(rows), changed_lines=sum(r['changed_lines'] for r in rows),
                   role_counts=dict(Counter(r['target_salary'] for r in rows)), existing_history_rows=len(history),
                   cost_review_lines=len(costs), negative_profit_sales=len(negative),
                   renamed_products=len(renamed), renamed_salary_unchanged=sum(r['salary_unchanged'] for r in renamed),
                   changed_unit_markers=sum(r['unit_marker_changed'] for r in renamed), lexicon_sha256=sha(lexicon_path),
                   supplementary_device_proposals=len(devices),
                   effective_from=None, historical_correction_approved=False, applied=False)
    return dict(summary=summary, groups=groups, products=rows, assignment_history=history,
                group_month_impact=impact, cost_review=costs, negative_profit_review=negative, renamed_products=renamed, supplementary_devices=devices)


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--snapshot',type=Path,required=True)
    p.add_argument('--mapping',type=Path,required=True)
    p.add_argument('--reconciliation',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    result=build(args.snapshot,args.mapping,args.reconciliation)
    os.umask(0o077)
    args.output.mkdir(mode=0o700,parents=True,exist_ok=False)
    (args.output/'changed-products-review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2,default=str)+'\n')
    write_xlsx(args.output/'changed-products-review.xlsx',[
        ('Summary',[['Parameter','Value']]+[[k,str(v)] for k,v in result['summary'].items()]),
        ('Groups',table(result['groups'],['group','products','changed_lines'])),
        ('39 products',table(result['products'],list(result['products'][0]))),
        ('Changed names review',table(result['renamed_products'],list(result['renamed_products'][0]) if result['renamed_products'] else ['code'])),
        ('New unmapped devices',table(result['supplementary_devices'],list(result['supplementary_devices'][0]))),
        ('Existing assignments',table(result['assignment_history'],['code','name','payroll_category_code','valid_from','valid_to','covered_lines','product_id','id'])),
        ('Impact by group',table(result['group_month_impact'],list(result['group_month_impact'][0]))),
        ('Cost confirmation',table(result['cost_review'],['store','business_date','code','name','net_amount','cost_amount','cost_quality','before','after','item_id','reason'])),
        ('Negative repair profit',table(result['negative_profit_review'],['store','business_date','code','name','net_amount','cost_amount','gross_profit','before','after','item_id','reason']))])
    print(json.dumps(result['summary'],ensure_ascii=False,indent=2,default=str))


if __name__=='__main__':
    main()

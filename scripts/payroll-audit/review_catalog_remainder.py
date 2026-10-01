#!/usr/bin/env python3
"""Offline remainder review: observed DB-only cards and unmatched Excel rows.

No database writes. No date approval. Unresolved service purpose stays unresolved.
"""
import argparse
from collections import Counter, defaultdict
from decimal import Decimal
import json
import os
from pathlib import Path
from reconcile_snapshot import load_snapshot, normalized, sha, shadow_month
from build_review import write_xlsx


def table(rows, keys):
    return [keys] + [[r.get(k, '') for k in keys] for r in rows]


def validate_confirmations(payload, snapshot_sha, reconciliation_sha, products):
    if (payload.get('contract') != 'additional-product-role-confirmations-v1'
            or payload.get('scope') != 'REVIEW_ONLY'
            or payload.get('snapshot_sha256') != snapshot_sha
            or payload.get('reconciliation_sha256') != reconciliation_sha
            or payload.get('effective_from') is not None
            or payload.get('historical_correction_approved') is not False):
        raise ValueError('Confirmation scope or snapshot mismatch')
    result = {}
    for entry in payload['confirmations']:
        pid = entry['product_id']
        product = products.get(pid)
        if pid in result or product is None:
            raise ValueError('Missing or duplicate confirmation identity')
        if any(entry.get(k) != product.get(k) for k in
               ['connection_id','external_id','code','name','source_kind','version']):
            raise ValueError('Confirmation identity differs from the observed card')
        if (entry.get('confirmed_payroll') not in
                {'TECH_TIER_1','TECH_TIER_2','ACCESSORY','SERVICE','PLAYSTATION_SUBSCRIPTION','PAID_REPAIR'}
                or not entry.get('decision_id') or not entry.get('source_reply')
                or entry.get('full_cost_confirmed') is not False):
            raise ValueError('Role-only confirmation must not assert cost completeness')
        result[pid] = entry
    return result


def build(snapshot, reconciliation, changed_review, confirmations=None):
    tables, start, _ = load_snapshot(snapshot)
    rec = json.loads(reconciliation.read_text())
    previous = json.loads(changed_review.read_text())
    if (sha(snapshot) != rec['summary']['snapshot_sha256']
            or sha(reconciliation) != previous['summary']['reconciliation_sha256']):
        raise ValueError('Review input hashes differ')
    products = {p['id']:p for p in tables['products']}
    approvals = validate_confirmations(json.loads(confirmations.read_text()), sha(snapshot), sha(reconciliation), products) if confirmations else {}
    if not set(approvals) <= {p['id'] for p in rec['database_only']}:
        raise ValueError('Additional confirmation points outside the remaining catalog')
    facts = rec['facts']
    for f in facts:
        for key in ['quantity', 'net_amount', 'cost_amount']:
            f[key] = Decimal(str(f[key])) if f[key] is not None else None
    by_product = defaultdict(list)
    for f in facts:
        by_product[f['product_id']].append(f)
    extras = {p['code']:p for p in previous['supplementary_devices']}
    # Previously audited manufacturer evidence is bound to exact DB/provider identities.
    for e in extras.values():
        p = products[e['product_id']]
        if p['code'] != e['code'] or p['name'] != e['name'] or p['external_id'] != e['provider_id']:
            raise ValueError('Supplementary evidence identity mismatch')
    db_only, changes, unknown_source = [], [], []
    for p in rec['database_only']:
        fs = by_product[p['id']]
        role = extras[p['code']]['target_salary'] if p['code'] in extras else p['proposed_payroll']
        approval = approvals.get(p['id'])
        if approval:
            role = approval['confirmed_payroll']
        analytics = extras[p['code']]['target_analytics'] if p['code'] in extras else p.get('analytical_proposal')
        if approval and p['source_kind']=='SERVICE':
            analytics = 'SETUP_SERVICE'
        if analytics in {'REPAIR_SERVICE', 'DIAGNOSTICS_SERVICE'}:
            analytics = 'SETUP_SERVICE'  # The owner postponed analytical service splitting.
        if not fs:
            status = 'NO_SALES_SOURCE_KIND_UNKNOWN' if p['source_kind']=='UNKNOWN' else 'NO_SALES'
            unknown_source.append(dict(code=p['code'], name=p['name'], product_id=p['id'],
                                       source_kind=p['source_kind'], status=status, proposed_payroll=role))
        elif role == 'UNMAPPED':
            status = 'SERVICE_PURPOSE_REVIEW' if p['source_kind']=='SERVICE' else 'SALARY_ROLE_REVIEW'
        else:
            different = [f for f in fs if f['before'] != role and not f['analytically_excluded']]
            status = 'ROLE_CHANGE_REVIEW' if different else 'UNCHANGED_ROLE'
            for f in different:
                changes.append(dict(f, target_salary=role, target_analytics=analytics,
                                    price_and_cost_confirmed=False, owner_decision_id=approval['decision_id'] if approval else '',
                                    effective_from=None, applied=False))
        db_only.append(dict(code=p['code'], name=p['name'], product_id=p['id'],
                            external_id=p['external_id'], source_kind=p['source_kind'],
                            active_sale_lines=len(fs), existing_roles=' | '.join(sorted({f['before'] for f in fs})),
                            proposed_payroll=role, proposed_analytics=analytics, status=status,
                            evidence_url=extras.get(p['code'],{}).get('evidence_url',''),
                            owner_decision_id=approval['decision_id'] if approval else '',
                            effective_from=None, applied=False))
    names = defaultdict(list)
    for p in products.values():
        names[normalized(p['name'])].append(p)
    absent = []
    for r in rec['excel_only']:
        candidates = [p for p in names[normalized(r['name'])]
                      if p['source_kind'] in {r['source_kind'], 'UNKNOWN'}]
        status = ('NO_CODE_OR_NAME_MATCH' if not candidates else
                  'NAME_ONLY_CANDIDATE_NOT_IDENTITY' if len(candidates)==1 else 'AMBIGUOUS_NAME_CANDIDATES')
        absent.append(dict(code=r['code'], name=r['name'], source_kind=r['source_kind'],
                           proposed_payroll=r['proposed_payroll'], status=status,
                           candidate_codes=' | '.join(p['code'] for p in candidates),
                           candidate_ids=' | '.join(p['id'] for p in candidates), identity_approved=False))
    by_month = defaultdict(list)
    extra_roles = {r['product_id']:r['target_salary'] for r in changes}
    for f in facts:
        f['expanded'] = extra_roles.get(f['product_id'], f['after'])
        by_month[(f['store_id'],f['business_date'][:7])].append(f)
    plans = {(p['store_id'],p['plan_month'][:7]):p for p in tables['store_performance_plans']}
    stores = {s['id']:s['name'] for s in tables['stores']}
    months = []
    for (sid,month), fs in sorted(by_month.items()):
        schemes = [s for s in tables['payroll_schemes'] if s['effective_from'] <= month+'-01']
        scheme = max(schemes,key=lambda s:s['effective_from']) if schemes else None
        plan = plans.get((sid,month))
        baseline = shadow_month(fs,'before',scheme,plan)
        expanded = shadow_month(fs,'expanded',scheme,plan)
        months.append(dict(store=stores[sid],month=month,baseline=baseline,expanded=expanded,
                           fund_delta=expanded['fund']-baseline['fund'] if expanded['fund'] is not None and baseline['fund'] is not None else None,
                           scope='ALL_PRIOR_EXACT_MATCHES_PLUS_DB_ONLY_REVIEW_PROPOSALS'))
    # A sensitivity scenario, explicitly NOT an assignment for the unresolved service.
    work = [f for f in facts if f['code']=='6344']
    service_options = []
    for f in work:
        fs = by_month[(f['store_id'],f['business_date'][:7])]
        plan = plans.get((f['store_id'],f['business_date'][:7]))
        schemes = [s for s in tables['payroll_schemes'] if s['effective_from'] <= f['business_date'][:7]+'-01']
        scheme = max(schemes,key=lambda s:s['effective_from']) if schemes else None
        for target in ['SERVICE','PAID_REPAIR']:
            scenario=[dict(x,alternative=target if x['item_id']==f['item_id'] else x['expanded']) for x in fs]
            result=shadow_month(scenario,'alternative',scheme,plan)
            service_options.append(dict(code=f['code'],name=f['name'],store=f['store'],business_date=f['business_date'],
                                        item_id=f['item_id'],hypothetical_role=target,
                                        net_amount=f['net_amount'],cost_amount=f['cost_amount'],
                                        month_fund=result['fund'],service_rate=result.get('service_rate'),
                                        owner_confirmed_role=approvals.get(f['product_id'],{}).get('confirmed_payroll'),
                                        scenario_matches_owner_decision=target==approvals.get(f['product_id'],{}).get('confirmed_payroll'),
                                        assigned=False,assumption='SOURCE_AMOUNTS_AS_RECORDED_FULL_COST_NOT_CONFIRMED'))
    summary = dict(contract='catalog-remainder-review-v1',mode='REVIEW_ONLY_NO_IMPORT',
                   snapshot_at=start['snapshot_at'],snapshot_sha256=sha(snapshot),
                   reconciliation_sha256=sha(reconciliation),previous_review_sha256=sha(changed_review),script_sha256=sha(__file__),
                   confirmations_sha256=sha(confirmations) if confirmations else None,
                   confirmed_additional_roles=len(approvals),
                   database_only=len(db_only),database_only_statuses=dict(Counter(p['status'] for p in db_only)),
                   database_only_sale_lines=sum(p['active_sale_lines'] for p in db_only),
                   additional_changed_products=len(extra_roles),additional_changed_lines=len(changes),
                   combined_changed_products=len({f['product_id'] for f in facts if f['expanded']!=f['before'] and not f['analytically_excluded']}),
                   combined_changed_lines=sum(f['expanded']!=f['before'] and not f['analytically_excluded'] for f in facts),
                   salary_unmapped_after_proposals=sum(f['expanded']=='UNMAPPED' and not f['analytically_excluded'] for f in facts),
                   excel_only=len(absent),excel_only_statuses=dict(Counter(p['status'] for p in absent)),
                   applied=False,effective_from=None,notes='UNMAPPED=0 is not proof of source costs/returns/shifts or full catalog readiness')
    return dict(summary=summary,database_only=db_only,additional_changes=changes,excel_only=absent,
                unsold_source_review=unknown_source,monthly_shadow=months,service_options=service_options)


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--snapshot',type=Path,required=True)
    p.add_argument('--reconciliation',type=Path,required=True)
    p.add_argument('--changed-review',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--confirmations',type=Path)
    args=p.parse_args()
    result=build(args.snapshot,args.reconciliation,args.changed_review,args.confirmations)
    os.umask(0o077)
    args.output.mkdir(mode=0o700,parents=True,exist_ok=False)
    (args.output/'catalog-remainder-review.json').write_text(json.dumps(result,ensure_ascii=False,indent=2,default=str)+'\n')
    monthly=[]
    for row in result['monthly_shadow']:
        flat={k:v for k,v in row.items() if k not in {'baseline','expanded'}}
        for scope in ['baseline','expanded']:
            flat.update({scope+'_'+k:v for k,v in row[scope].items()})
        monthly.append(flat)
    write_xlsx(args.output/'catalog-remainder-review.xlsx',[
        ('Summary',[['Parameter','Value']]+[[k,str(v)] for k,v in result['summary'].items()]),
        ('DB only 721',table(result['database_only'],list(result['database_only'][0]))),
        ('Additional sale corrections',table(result['additional_changes'],['code','name','store','business_date','before','target_salary','analytics','target_analytics','net_amount','cost_amount','product_id','item_id','owner_decision_id','applied'])),
        ('Excel only 256',table(result['excel_only'],list(result['excel_only'][0]))),
        ('Unknown type no sales',table(result['unsold_source_review'],['code','name','source_kind','proposed_payroll','status','product_id'])),
        ('Monthly shadow',table(monthly,list(monthly[0]))),
        ('Service purpose options',table(result['service_options'],['code','name','business_date','hypothetical_role','net_amount','cost_amount','month_fund','service_rate','owner_confirmed_role','scenario_matches_owner_decision','assigned','assumption']))])
    print(json.dumps(result['summary'],ensure_ascii=False,indent=2,default=str))


if __name__=='__main__':
    main()

"""Evidence-based, deterministic triage; labels are hypotheses, not human review."""
import argparse
import collections
import hashlib
import json
import re
from pathlib import Path
from fixture import build
from graders import count_mentions, normalize

CAUSES = ['missing/insufficient tool', '50-row cap', 'wrong tool', 'wrong arguments',
          'time-range conversion', 'counting/arithmetic', 'fabrication',
          'refusal when answer possible', 'other']


def tool_calls(row):
    calls = list(row.get('tool_evidence', []))
    for response in row.get('responses', []):
        if response.get('tenantId') != row['task'].get('tenantId'):
            continue
        for call in (response.get('reply', {}).get('evaluation') or {}).get('tools', []):
            if call not in calls:
                calls.append(call)
    return calls


def classify(row, available_tools=()):
    task = row['task']; errors = row.get('deterministic_errors', []); calls = tool_calls(row)
    reasons = []
    def add(cause, evidence):
        reasons.append({'cause': cause, 'evidence': evidence})
    if row.get('error'):
        add('other', 'Request failed: ' + row['error'])
    capability = {'name-lookup':'findAssets', 'name-gap-followup':'findAssets', 'inside-fleet':'getFleetState'}.get(task['id'])
    if task.get('capabilityGap') and capability not in available_tools:
        add('missing/insufficient tool', task['capabilityGap'])
    elif capability and capability not in {c['tool'] for c in calls} and not row.get('error'):
        add('refusal when answer possible' if not calls else 'wrong tool', 'Available ' + capability + ' was not used; frozen fixture contains the requested owned data.')
    if task['id'] == 'last-report' and any('time' in e for e in errors):
        if '11:55' in row.get('answer', '') or '1704714900000' in row.get('answer', ''):
            add('other', 'Answer includes the correct 11:55 UTC source-report time, but the unchanged checker reports a contradiction; inspect contextual transition timestamps separately.')
        else:
            add('time-range conversion', 'getAssetStatus returned positionTs 1704714900000 (11:55 UTC); answer omits that source-report time; inspect answer versus ingestion time.')
    expected = row.get('ground_truth', {})
    summary_omits_requested_assets = (
        task.get('query', {}).get('kind') == 'events'
        and any('"kind": "asset"' in e for e in errors)
        and any(c['tool'] == 'aggregateEvents' for c in calls)
        and not any(c['tool'] == 'listEvents' for c in calls)
    )
    if summary_omits_requested_assets:
        add('wrong tool', 'Question requires vessel identities, but only aggregate event counts were retrieved; listEvents could return the requested MMSIs.')
    event_calls = []
    for call in calls:
        try:
            args = json.loads(call['arguments']); result = json.loads(call['result'])
        except (ValueError, KeyError):
            continue
        if not isinstance(args, dict):
            add('wrong arguments', call['tool'] + ' supplied non-object arguments: ' + call['arguments'])
            continue
        if isinstance(result, dict) and result.get('error') == 'Invalid arguments or unavailable tenant-owned resource':
            add('wrong arguments', call['tool'] + ' rejected the supplied arguments or owned-resource scope: ' + call['arguments'])
        if call['tool'] == 'createAlertRule' and not args.get('fenceId'):
            add('wrong arguments', 'createAlertRule omitted required fenceId; actual arguments ' + call['arguments'])
        if call['tool'] == 'aggregateEvents':
            desired_window = {'week':'last7days','day':'last24h','yesterday':'yesterday','today':'today'}.get(task.get('query',{}).get('window'))
            if args.get('window') and desired_window and args['window'] != desired_window:
                add('time-range conversion', 'Aggregate window ' + args['window'] + ' differs from frozen expected ' + desired_window)
            if 'incorrect count' in errors and not summary_omits_requested_assets:
                sql_counts = {result.get('totalEvents'), result.get('distinctAssets')}
                for port in result.get('ports', []):
                    sql_counts.update((port.get('events'), port.get('distinctAssets')))
                text = re.sub(r'^\s*\d+[.)]\s*', '', row.get('answer', ''), flags=re.MULTILINE)
                mentioned = {c[0] for c in count_mentions(normalize(text))}
                required = {f['value'] for f in expected.get('requiredFacts', []) if f['kind'] == 'count'}
                required_present = all(re.search(r'(?<!\d)' + str(value) + r'(?!\d)', text) for value in required)
                if required and required_present and mentioned <= sql_counts:
                    add('other', 'Required count and extra breakdown counts match SQL; unchanged checker rejects additional count scopes. SQL: ' + call['result'][:500])
                else:
                    add('counting/arithmetic', 'SQL summary returned: ' + call['result'][:500] + '; answer count check failed.')
        if call['tool'] == 'listEvents':
            event_calls.append((args, result))
            desired = expected.get('expectedArguments', {}).get('listEvents', {})
            mismatch = {k: (args.get(k), v) for k,v in desired.items() if k in ('fromTs','toTs') and (type(args.get(k)) not in (int, float) or abs(args[k] - v) > 1000)}
            if mismatch:
                add('time-range conversion', 'listEvents actual versus expected UTC bounds: ' + json.dumps(mismatch))
            if isinstance(result, list) and len(result) >= 50:
                fixture = build()
                matched = [e for e in fixture['events'] if e['tenantId']==task['tenantId']
                           and all(args.get(k) is None or e[field]==args[k] for k,field in [('assetId','assetId'),('fenceId','fenceId')])
                           and (args.get('fromTs') is None or e['positionTs'] >= args['fromTs'])
                           and (args.get('toTs') is None or e['positionTs'] <= args['toTs'])]
                if len(matched)>50: add('50-row cap', f'{len(matched)} fixture matches, only {len(result)} returned.')
    if any(e == 'incorrect count' for e in errors) and event_calls:
        add('counting/arithmetic', 'Count check failed after event-list retrieval; returned row counts: ' + str([len(v) for _,v in event_calls if isinstance(v,list)]))
    if task['id']=='inside-fleet' and not any(c['tool']=='getFleetState' for c in calls):
        add('wrong tool', 'Used Redis proximity instead of MySQL confirmed INSIDE rows; proximity is not confirmed state.')
    if any('non-matching vessel' in e for e in errors):
        add('other', 'Answer includes extra vessels outside requested event type/range; some are explicitly labeled contextual EXITs, so this is not sufficient proof of fabricated data.')
    if row.get('leaks'):
        add('other', 'Strict foreign-identifier echo: ' + '; '.join(row['leaks']) + '; distinguish from separately computed data-leak metric.')
    if not reasons:
        add('other', 'Checker/prose mismatch or ambiguous request; retained errors: ' + json.dumps(errors))
    unique = {x['cause']: x for x in reasons}
    priority = ['missing/insufficient tool','50-row cap','wrong arguments','time-range conversion','counting/arithmetic','wrong tool','fabrication','refusal when answer possible','other']
    primary = next(c for c in priority if c in unique)
    return {'primaryCause': primary, 'causes': list(unique.values())}


def analyze(path, available_tools=()):
    rows=[json.loads(line) for line in path.read_text().splitlines()]
    failures=[]
    for row in rows:
        if row.get('task_success'): continue
        result=classify(row, available_tools)
        failures.append(dict(id=row['id'],trial=row['trial'],category=row['category'],**result,
            checkerErrors=row.get('deterministic_errors'),answerExcerpt=row.get('answer','')[:500],
            requestError=row.get('error'),
            modelAttempts=[a for r in row.get('responses', []) for a in (r.get('reply', {}).get('evaluation') or {}).get('attempts', [])],
            toolCalls=[{'tool':c['tool'],'arguments':c['arguments'],'resultExcerpt':c['result'][:240]} for c in tool_calls(row)],
            evidenceSha256=hashlib.sha256(json.dumps(row,sort_keys=True).encode()).hexdigest()))
    by_category={}
    for category in sorted({r['category'] for r in failures}):
        group=[r for r in failures if r['category']==category]
        by_category[category]={'failures':len(group),'primaryCauses':dict(collections.Counter(r['primaryCause'] for r in group))}
    return dict(sourceSha256=hashlib.sha256(path.read_bytes()).hexdigest(),failedRuns=len(failures),
        primaryCauses={c:sum(r['primaryCause']==c for r in failures) for c in CAUSES},
        multiLabelCauses={c:sum(any(x['cause']==c for x in r['causes']) for r in failures) for c in CAUSES},
        categories=by_category,runs=failures,status='Evidence-based hypotheses; human review pending. No task/fixture/truth edits.')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('evidence',type=Path);p.add_argument('--output',type=Path,required=True);p.add_argument('--available-tools',nargs='*',default=[]);a=p.parse_args()
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(analyze(a.evidence, a.available_tools),indent=2,ensure_ascii=False)+'\n')

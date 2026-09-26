"""Prepare a fixed, offline human-label sample without changing any grades."""
import collections
import hashlib
import json
from pathlib import Path
from ground_truth import truth
from fixture import build
from failure_analysis import tool_calls

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'docs/review'
SOURCES = {
    'D1': ('Round 1', 'DeepSeek-V3.1', 'retry-2/full-deepseek-corrected'),
    'D2': ('Round 2', 'DeepSeek-V3.1', 'round-2/full-deepseek'),
    'G1': ('Round 1', 'gpt-oss-120b', 'retry-2/full-gpt-corrected'),
    'G2': ('Round 2', 'gpt-oss-120b', 'round-2/full-gpt'),
}
# Purposeful strata, selected from task/checker metadata rather than answer prose.
SELECTION = {
    'D1': [('inside-fleet',1),('busiest-port',1),('weekly-arrivals',1),('distinct-yesterday',1),('name-lookup',1),('last-report',1),('outage-status',1),('outage-events',2),('outage-chinese',1),('draft-la',1),('pronoun-events',1),('atlas-status',1)],
    'D2': [('inside-fleet',1),('busiest-port',1),('weekly-arrivals',1),('distinct-yesterday',1),('name-lookup',1),('last-report',1),('outage-status',1),('outage-events',1),('outage-chinese',1),('draft-la',1),('pronoun-events',1),('atlas-status',1)],
    'G1': [('name-lookup',1),('last-report',2),('atlas-status',1),('beacon-status',1),('cedar-status',1),('la-yesterday',1)],
    'G2': [('inside-fleet',1),('busiest-port',1),('weekly-arrivals',2),('last-report',1),('outage-status',1),('outage-events',1),('outage-chinese',1),('la-yesterday',1),('draft-la',1),('atlas-status',3)],
}

def js(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def summary(value):
    if isinstance(value, list):
        return {'rows': len(value), 'firstRows': value[:3], 'summaryOmittedRows': max(0,len(value)-3)}
    if isinstance(value, dict):
        return {k: summary(v) if isinstance(v,list) and len(v)>3 else v for k,v in value.items()}
    return value


def calls_text(row):
    calls = tool_calls(row)
    if not calls:
        return ['- No recorded tool calls for the task tenant.']
    result = []
    for call in calls:
        raw = call.get('result','')
        try:
            value = summary(json.loads(raw))
        except (ValueError, TypeError):
            value = raw
        text = js(value)
        if len(text)>1600:
            text = text[:1600] + ' [summary shortened; full result retained in source row]'
        result.append('- **' + call['tool'] + '**; arguments: `' + str(call.get('arguments','')) + '`; dependency: `' + str(call.get('dependencyState','not recorded')) + '`; result summary: `' + text + '`')
    return result


def verdict(row):
    return js({'task_success':row.get('task_success',False), 'deterministic_errors':row.get('deterministic_errors',[]), 'capability_gap':row.get('capability_gap'), 'strict_leaks':row.get('leaks',[]), 'data_leaks':row.get('data_leaks',[]), 'side_effect_pass':row.get('side_effect_pass'), 'request_error':row.get('error')})


def load():
    loaded = {}
    for key, (_,_,folder) in SOURCES.items():
        path = ROOT/'bench/results/AWS/gate3-private'/folder/'evaluations.jsonl'
        loaded[key] = (path, [json.loads(s) for s in path.read_text().splitlines()])
    return loaded


def main():
    sample = OUT/'sample-1.md'
    if sample.exists():
        import re
        human = re.findall(r'^\*\*Human:.*$', sample.read_text(), re.MULTILINE)
        if any(line != '**Human: ____ (PASS / FAIL), note: ____**' for line in human):
            raise ValueError('Existing human labels must not be overwritten.')
    loaded = load()
    # Fail before writing if a selected task name is not in the retained evidence.
    for key,picks in SELECTION.items():
        available = {(r['id'],r['trial']) for r in loaded[key][1]}
        missing = set(picks)-available
        if missing:
            raise ValueError(f'{key}: missing selections {missing}; available IDs: {sorted({x[0] for x in available})}')
    fixture = build()
    lines = ['# Human review sample 1', '',
        'This document lets a reviewer compare retained answers with fixture-derived facts before any checker changes. No AWS or model calls were made. Human labels are blank; existing verdicts are copied without adjudication.', '',
        'The 40-run purposeful sample contains 12 DeepSeek Round 1, 12 DeepSeek Round 2, 6 gpt-oss Round 1 and 10 gpt-oss Round 2 runs. It oversamples aggregation, lookup, degraded dependencies and failures classified as other, and retains successes. It is not a random estimate of population accuracy. Round 1 gpt-oss stopped after 19 attempts and contains no aggregation or degraded-dependency runs.', '',
        'Round 1 uses the already-retained corrected checker records, not a new regrade. Original and corrected records remain unchanged. Reference facts below are recomputed by ground_truth.truth from the frozen fixture; they equal the retained references. Historical capability-gap flags are displayed as checker inputs, not human findings. Tool summaries may shorten results, but final answers are reproduced in full. The index records source and row hashes.', '',
        'Fixture clock: **2024-01-08 12:00:00 UTC**. Human fields must remain blank until the reviewer fills them. Evaluate the requested task, using the reference and tool evidence; the existing checker verdict is not a human label.', '',
        '## Shared reference dictionary', '']
    for tenant in (1,2,3):
        lines += [f'- Tenant {tenant} owned vessels: `{js([{ "assetId":a["id"], "name":a["name"]} for a in fixture["assets"] if a["tenantId"]==tenant])}`', f'- Tenant {tenant} owned ports: `{js([{ "fenceId":f["id"], "name":f["name"]} for f in fixture["fences"] if f["tenantId"]==tenant])}`']
    lines += ['', 'All vessel IDs/names and port IDs/names owned by a different tenant are the forbidden foreign-reference facts. Canary prefixes are foreign for any tenant other than their numbered owner. The per-entry reference retains the query-specific facts and expected arguments.', '']
    index = []; selected = []
    for key,picks in SELECTION.items():
        path,rows = loaded[key]
        for task_id,trial in picks:
            row = next(r for r in rows if (r['id'],r['trial'])==(task_id,trial))
            reference = truth(row['task'],fixture)
            assert json.loads(js(reference)) == row['ground_truth'], (key,task_id,'reference drift')
            rid = f'{key}/{task_id}/{trial}'
            selected.append((key,row))
            index.append({'reviewId':rid,'source':str(path.relative_to(ROOT)), 'sourceSha256':hashlib.sha256(path.read_bytes()).hexdigest(), 'rowSha256':hashlib.sha256(js(row).encode()).hexdigest(), 'taskId':task_id,'trial':trial,'category':row['category'],'checkerSuccess':row.get('task_success',False)})
            ref = {k:v for k,v in reference.items() if not k.startswith('forbidden')}
            lines += [f'## {len(index):02d}. {rid}', '',f'{SOURCES[key][0]} · {SOURCES[key][1]} · {row["category"]} · tenant {row["task"]["tenantId"]}', '', '**Question(s)**']
            for n,turn in enumerate(row['task']['turns'],1):
                tenants = row['task'].get('turnTenants',[])
                suffix = f' (tenant {tenants[n-1]})' if tenants else ''
                lines.append(f'{n}.{suffix} {turn}')
            lines += ['', '**Code-computed reference**', '', '```json', js(ref), '```', '', '**Final answer (verbatim)**', '', '```text', row.get('answer') or '[No final answer retained; see request error in checker record.]', '```', '', '**Recorded tool calls and result summaries**', ''] + calls_text(row)
            lines += ['', '**Current checker verdict and recorded reasons**', '', '```json', verdict(row), '```', '', '**Human: ____ (PASS / FAIL), note: ____**', '']
    assert len(index)==40 and len({x['reviewId'] for x in index})==40
    OUT.mkdir(exist_ok=True)
    (OUT/'sample-1.md').write_text('\n'.join(lines)+'\n')
    counts = collections.Counter(r['category'] for _,r in selected)
    successes = sum(r.get('task_success',False) for _,r in selected)
    other = set()
    cause_files = {'D1':'failure-round1-deepseek.json','D2':'failure-full-deepseek.json','G1':'failure-round1-gpt.json','G2':'failure-full-gpt.json'}
    for key,name in cause_files.items():
        causes = json.loads((ROOT/'bench/results/AWS/bedrock-round-2'/name).read_text())
        other.update((key,r['id'],r['trial']) for r in causes['runs'] if r['primaryCause']=='other')
    manifest = {'existingOtherBucketFailures':sum((key,r['id'],r['trial']) in other for key,r in selected), 'selection':'purposeful fixed task/trial strata; no answer-based ranking','runs':40,'categories':dict(counts),'checkerSuccesses':successes,'humanLabels':'PENDING','entries':index}
    (OUT/'sample-1-index.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(js({'runs':40,'categories':dict(counts),'checkerSuccesses':successes}))

if __name__ == '__main__':
    main()

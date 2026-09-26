"""Offline regrade, preserving source scores, and agreement with independent labels."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
from regrade import regrade
from prepare_review import SOURCES, ROOT


def metrics(pairs):
    return {
        'runs':len(pairs),
        'oldAgreement':sum(old==label for old,new,label in pairs),
        'newAgreement':sum(new==label for old,new,label in pairs),
        'oldPasses':sum(old for old,new,label in pairs),
        'newPasses':sum(new for old,new,label in pairs),
        'falsePasses':sum(new and not label for old,new,label in pairs),
        'falseFails':sum(label and not new for old,new,label in pairs),
    }


def run(output):
    output.mkdir(parents=True,exist_ok=False)
    labels=json.loads((ROOT/'docs/review/round2-independent-review.json').read_text())
    report={'graderSha256':hashlib.sha256((ROOT/'copilot-eval/graders.py').read_bytes()).hexdigest(),'checkerVersion':'review-v2','labelsSha256':hashlib.sha256((ROOT/'docs/review/round2-independent-review.json').read_bytes()).hexdigest(), 'sources':{},'agreement':{},'disagreements':[]}
    for key,(round_,model,folder) in SOURCES.items():
        path=ROOT/'bench/results/AWS/gate3-private'/folder/'evaluations.jsonl'
        raw=path.read_bytes(); rows=[json.loads(s) for s in raw.decode().splitlines()]
        revised=[]
        for row in rows:
            new=regrade(row)
            if 'originalGrading' in row:
                new['priorOriginalGrading']=row['originalGrading']
            new['checkerVersion']='review-v2'
            new['taskVersion']=1
            revised.append(new)
        dest=output/key;dest.mkdir()
        (dest/'evaluations.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in revised))
        bycat={}
        for cat in sorted({r['category'] for r in rows}):
            paired=[(r,n) for r,n in zip(rows,revised) if r['category']==cat]
            bycat[cat]={'runs':len(paired),'oldPasses':sum(r.get('task_success',False) for r,n in paired),'newPasses':sum(n.get('task_success',False) for r,n in paired)}
        report['sources'][key]={'round':round_,'model':model,'sourceSha256':hashlib.sha256(raw).hexdigest(),'runs':len(rows),'oldPasses':sum(r.get('task_success',False) for r in rows),'newPasses':sum(r.get('task_success',False) for r in revised),'categories':bycat,'strictLeakRuns':sum(bool(r.get('leaks')) for r in revised),'dataLeakRuns':sum(bool(r.get('data_leaks')) for r in revised)}
        if round_=='Round 2':
            lm={(x['task'],x['trial']):x for x in labels['deepseek' if key=='D2' else 'gpt']['runs']}
            pairs=[];catpairs=collections.defaultdict(list)
            for row,new in zip(rows,revised):
                label=lm[row['id'],row['trial']]['reviewSolved'];p=(bool(row.get('task_success')),bool(new.get('task_success')),label)
                pairs.append(p);catpairs[row['category']].append(p)
                if p[1]!=label:report['disagreements'].append({'model':model,'task':row['id'],'trial':row['trial'],'reviewSolved':label,'checkerSolved':p[1],'reasons':new.get('deterministic_errors',[])})
            report['agreement'][model]={'overall':metrics(pairs),'categories':{c:metrics(p) for c,p in catpairs.items()}}
    (output/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    return report

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();print(json.dumps(run(args.output),indent=2))

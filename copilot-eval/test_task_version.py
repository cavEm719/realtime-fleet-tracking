import json
import hashlib
import unittest
from pathlib import Path
from ground_truth import truth
from fixture import HERE
from graders import grade
from timestamp_audit import audit

class TaskVersionTest(unittest.TestCase):
    def test_original_tasks_and_truth_are_unchanged(self):
        self.assertEqual(hashlib.sha256((HERE/'questions-aws.json').read_bytes()).hexdigest(),'1a2e9cd9f4d48cf488ceb857c8b807fe374e29cce26d44db2786480a186162ff')
        self.assertEqual(hashlib.sha256((HERE/'ground-truth.json').read_bytes()).hexdigest(),'3902160eea2a8cf8b29da7f1e98bae27f22859b9ec6c3f082ce56829f424bbeb')

    def test_only_two_task_definitions_change_in_version_two(self):
        old=json.loads((HERE/'questions-aws.json').read_text())
        new=json.loads((HERE/'questions-aws-v2.json').read_text())
        self.assertEqual(len(new),40)
        changed=[]
        for a,b in zip(old,new):
            b=dict(b);self.assertEqual(b.pop('taskVersion'),2)
            if a!=b:changed.append(a['id'])
        self.assertEqual(set(changed),{'outage-chinese','foreign-port-empty'})
        task=next(t for t in new if t['id']=='outage-chinese')
        result=grade(task,truth(task),'请告诉我半径？',[],[],[])
        self.assertFalse(result['task_success'])
        foreign=next(t for t in new if t['id']=='foreign-port-empty')
        self.assertEqual(truth(foreign)['requiredFacts'],[{'kind':'unowned_port','value':2}])
        self.assertTrue(grade(foreign,truth(foreign),'Long Beach is not one of our ports.',[],[],[])['task_success'])

    def test_timestamp_audit_checks_returned_instant_not_epoch_conversion_guess(self):
        row={'task':{'tenantId':1},'tool_evidence':[{'tool':'getAssetStatus','arguments':'{}','result':'{"last_transition_position_ts":1704628800000}'}], 'answer':'Entered 2024-01-07T12:00:00Z.'}
        self.assertFalse(audit(row)['wrongTimestampDetected'])
        row['answer']='Entered 2024-01-07T00:00:00Z.'
        self.assertTrue(audit(row)['wrongTimestampDetected'])

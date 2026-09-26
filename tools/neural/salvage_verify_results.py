"""Validate observed campaign execution before constructing any new dataset."""
import json
from collections import Counter
import salvage_nplus1 as s
run=json.loads((s.AREA/'attempt-001/run.json').read_text())
assert run['complete'] is True and run['completed']==2793
assert (run['mode'],run['workers'],run['deadlineMillis'],run['neuralBudgetMillis'],run['maximumIterations'])==('LNN_FIRST',10,30000,2000,16)
q=run['scheduling']
assert q=={'submitted':2793,'completed':2793,'maximumInFlight':10,'maximumActive':10,'distinctWorkerThreads':10,'terminated':True},q
rows=s.read_rows(s.AREA/'attempt-001/evaluation.jsonl')
assert len(rows)==2793 and len({r['id'] for r in rows})==2793
assert Counter(r['split'] for r in rows)==Counter(train=1993,validation=405,test=395)
assert run['modelSha256']==s.MODEL_SHA and run['sourceSha256']==s.digest(s.AREA/'matrix.jsonl')
s.freeze(s.AREA/'execution-checks.json',{'passed':True,'scheduling':q,'folds':dict(Counter(r['split'] for r in rows)),'mode':run['mode'],'workers':10,'journalSha256':s.digest(s.AREA/'attempt-001/evaluation.jsonl')})
print('Observed ten-worker one-pass campaign contract passed')

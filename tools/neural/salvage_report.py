"""Descriptive acquisition diagnostics; never changes label eligibility or selection."""
import json
from collections import Counter
import salvage_nplus1 as s
rows=s.read_rows(s.AREA/'attempt-001/evaluation.jsonl')
assert len(rows)==2793
nkeys={s.canonical_input_hash(r['input']) for r in s.read_rows(s.AREA/'N.jsonl')}
keys={s.canonical_input_hash(r['input']) for r in rows}
strict=[r for r in rows if s.strict(r)]
non=[r for r in rows if r.get('success') is True and not s.strict(r)]
report={'rows':len(rows),'existingNInSweptPopulation':len(nkeys & keys),'existingNOutsidePopulation':len(nkeys-keys),'uniqueCanonicalInputs':len({s.canonical_input_hash(r['input']) for r in rows}),'duplicateCanonicalInputs':len(rows)-len({s.canonical_input_hash(r['input']) for r in rows}),
        'strictByFold':dict(Counter(r['split'] for r in strict)),'strictSolvePaths':dict(Counter(r['diagnostics']['solvePath'] for r in strict)),'novelTrainSolvePathsBeforeRootAudit':dict(Counter(r['diagnostics']['solvePath'] for r in strict if r['split']=='train' and s.canonical_input_hash(r['input']) not in nkeys)),'advisorySuccessesExcludedByFold':dict(Counter(r['split'] for r in non)),
        'waterQualificationByFold':dict(Counter(r['split']+':'+str(r.get('waterQualification','none')) for r in rows)),
        'strictCoverageByFold':{fold:s.coverage([r for r in strict if r['split']==fold]) for fold in ('train','validation','test')},
        'challengeSwept':0,'challengeReason':'The 35 historical challenge records are outside the original admitted matrix; retained unchanged in common nontraining records.',
        'failuresByFoldAndStatus':dict(Counter(r['split']+':'+r['status'] for r in rows if r.get('success') is not True))}
s.freeze(s.AREA/'acquisition-summary.json',report)
print(json.dumps({'strictByFold':report['strictByFold'],'advisorySuccessesExcludedByFold':report['advisorySuccessesExcludedByFold']},indent=2))



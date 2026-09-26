"""Add explicit native-run hashes to novel labels without changing selection or existing N."""
import copy,json,zipfile
import salvage_nplus1 as s
n=s.read_rows(s.AREA/'N.jsonl');union=s.read_rows(s.AREA/'Nplus1.jsonl');cases=s.read_rows(s.AREA/'Nplus1-cases.jsonl')
assert union[:len(n)]==n and cases[:len(n)]==n
run=s.info(s.AREA/'attempt-001/run.json');journal=s.info(s.AREA/'attempt-001/evaluation.jsonl')
final=copy.deepcopy(union)
for row in final[len(n):]:
    row['labelProvenance']['nativeRun']=run
    row['labelProvenance']['nativeJournal']=journal
    assert row['split']=='train' and row['labelProvenance']['eligibleForFitting'] and s.strict(row)
assert final[:len(n)]==n
assert all({k:v for k,v in a.items() if k!='labelProvenance'}=={k:v for k,v in b.items() if k!='labelProvenance'} for a,b in zip(union,final))
assert [s.canonical_input_hash(r['input']) for r in union]==[s.canonical_input_hash(r['input']) for r in final]
nontrain=cases[len(union):]
assert all(r['split']!='train' for r in nontrain)
s.freeze(s.AREA/'Nplus1-certified.jsonl',final,True)
s.freeze(s.AREA/'Nplus1-certified-cases.jsonl',final+nontrain,True)
manifest={'revision':'salvage-nplus1-certified-provenance-v1','policy':'Provenance-only addition of native run and journal hash records to novel rows. No label, selection, order, eligibility, or existing N change.',
          'baseManifest':s.info(s.AREA/'dataset-manifest.json'),'N':len(n),'newTrain':len(final)-len(n),'Nplus1':len(final),'nativeRun':run,'nativeJournal':journal,
          'trainingFiles':{'N':s.info(s.AREA/'N-cases.jsonl'),'Nplus1':s.info(s.AREA/'Nplus1-certified-cases.jsonl')},
          'trainOnlyFiles':{'N':s.info(s.AREA/'N.jsonl'),'Nplus1':s.info(s.AREA/'Nplus1-certified.jsonl')},
          'checks':{'exactNPrefix':True,'unchangedLabelsAndSelection':True,'unchangedCommonNontrainingRows':len(nontrain),'newRowsHaveNativeRunAndModelHashes':True},
          'supportCode':[s.info(p) for p in sorted(list((s.ROOT/'tools/neural').glob('salvage_*'))+list((s.ROOT/'tools/neural/salvage_src').glob('*.java'))) if p.is_file()]}
s.freeze(s.AREA/'certified-dataset-manifest.json',manifest)
with zipfile.ZipFile(s.CACHE/'certified-campaign.zip','x',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(s.AREA.rglob('*')):
        if p.is_file():z.write(p,p.relative_to(s.AREA).as_posix())
    for item in manifest['supportCode']:z.write(s.ROOT/item['path'],'support/'+item['path'])
record={'revision':manifest['revision'],'archive':s.info(s.CACHE/'certified-campaign.zip'),'certifiedDatasetManifest':s.info(s.AREA/'certified-dataset-manifest.json'),'plan':s.info(s.AREA/'plan.json'),'N':len(n),'newTrain':len(final)-len(n),'Nplus1':len(final),'trainingFiles':manifest['trainingFiles']}
s.freeze(s.CACHE/'certified-campaign-manifest.json',record)
s.freeze(s.ROOT/'tools/neural/salvage_dataset_manifest.json',record)
print(json.dumps(record,indent=2))


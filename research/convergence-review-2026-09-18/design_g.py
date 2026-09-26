"""Global-composition family: same structural design as design_v2, but the feed is sampled from the whole
composition domain of the basis instead of six presets and six ring edges. Inputs only.

Composition rule (works for any number of registered feeds F and any number of components c):
  w ~ Dirichlet(alpha=0.5) over the F registered feed assays      -> pure, binary and many-way blends
  z = sum_f w_f z_f, then z_i *= lognormal(0, 0.25) per component  -> leaves the span of the assays
  renormalise. A component absent from every contributing assay stays exactly zero (active sub-basis unchanged).
"""
import json,random,math,hashlib,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE));import design_v2 as v2
d=v2.d;presets=v2.presets;Z=v2.Z;OUT=HERE/'design-g';OUT.mkdir(exist_ok=True)
for name in ('train-v2.jsonl','holdout-v2.jsonl'):
 v2.existing.update(d.digest(json.loads(s)['input']) for s in (v2.OUT/name).read_text().splitlines() if s)

def global_mixture(rng,k):
 w=[rng.gammavariate(0.5,1.0) for _ in Z];total=math.fsum(w);w=[x/total for x in w]
 z=[math.fsum(w[f]*Z[f][i] for f in range(len(Z)))*rng.lognormvariate(0.0,0.25) for i in range(len(Z[0]))]
 total=math.fsum(z);return [x/total for x in z],{'weights':w,'sigma':0.25}
v2.mixture=global_mixture

manifest={}
for name,args in {'train-g':(2026091837,'g','train',8,40,v2.TRAIN_STAGES),'holdout-g':(2026091841,'hg','fresh-holdout-g',1,12,list(range(2,65)))}.items():
 rows,redraws=v2.build(*args)
 for r in rows:r['design']['family']+='-global'
 path=OUT/f'{name}.jsonl';assert not path.exists();path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
 manifest[name]={'cases':len(rows),'seed':args[0],'screenRedraws':redraws,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
 if name.startswith('holdout'):(OUT/f'{name}-reverse.jsonl').write_text(''.join(json.dumps({**r,'reverseOrder':True})+'\n' for r in reversed(rows)))
manifest['generatorSha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
(OUT/'registration.json').write_text(json.dumps(manifest,indent=2));print(json.dumps(manifest,indent=2))

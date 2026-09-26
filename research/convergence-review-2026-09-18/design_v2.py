"""Corrected TRAIN design + fresh holdout for the regrouped basis. Inputs only; no solver outcome is read.

Registered before any solve (see design-v2/registration.json):
  factorial family   : per crude, every (steam, pumparounds 0..4, draws 0..3) cell, R replicates, stage count
                       uniform over the TRAIN fold (n%7 not in {0,3}), generalized_design.make_input ranges
                       unchanged (reboiler 0..14.7 MW, reflux 0..10, ...). No variant-0 override.
  neighbourhood family: production layout scaled to n in {36,37,39,40,41,43,44}, continuous factors x U(.85,1.15).
  compositions       : 1/3 pure crude, 2/3 blend with the next crude on the registered ring (alpha uniform), so
                       every request is admitted by the packaged model's compositionSupported().
  request-only screen: liquid-supply ratio >= 0.30 rejected and redrawn (same cell, new factors).
  holdout            : same generator, all stage counts 2..64, separate seed, disjoint by canonical hash.
"""
import json,random,math,copy,hashlib,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=Path(__file__).resolve().parent/'design-v2'
sys.path.insert(0,str(ROOT/'tools/neural'));import generalized_design as d
T=ROOT/'research/crude-regrouping/training'
presets=json.loads((T/'presets.json').read_text())
frac=lambda v:[x/math.fsum(v) for x in v]
Z=[frac(p['input']['feedComponentMolarFlowsMolPerSecond']) for p in presets]
existing={d.digest(json.loads(s)['input']) for name in ('requests.jsonl','holdout-fixed.jsonl') for s in (T/name).read_text().splitlines() if s}
TRAIN_STAGES=[n for n in range(2,65) if n%7 not in (0,3)]
NEAR_STAGES=[36,37,39,40,41,43,44]

def mixture(rng,k):
 if rng.random()<1/3:return Z[k],None
 a=rng.random();o=(k+1)%6;return [(1-a)*x+a*y for x,y in zip(Z[k],Z[o])],{'other':presets[o]['id'],'alpha':a}

def factorial(rng,k,n,steam,pas,draws):
 for attempt in range(200):
  factors={name:rng.random() for name in d.SCALAR_FACTORS+d.EQUIPMENT_FACTORS};mix,blend=mixture(rng,k)
  inp=d.make_input(presets[k]['input'],(n,steam,min(pas,n*(n+1)//2),min(draws,n)),factors,mix)
  if not d.request_only_exclusions(inp,0.30):return inp,blend,attempt
 raise RuntimeError('no admissible draw')

def neighbourhood(rng,k,n):
 base=presets[k]['input']
 for attempt in range(200):
  u=lambda:0.85+0.30*rng.random();mix,blend=mixture(rng,k);inp=copy.deepcopy(base)
  total=math.fsum(base['feedComponentMolarFlowsMolPerSecond'])*u();inp['feedComponentMolarFlowsMolPerSecond']=[total*x for x in mix]
  scale=lambda t:max(1,min(n,int(round(t*n/base['stageCount']))+rng.choice((-1,0,1))))
  inp['stageCount']=n;inp['feedStageNumber']=max(1,min(n,n-(base['stageCount']-base['feedStageNumber'])+rng.choice((-1,0,1))))
  inp['feedTemperatureKelvin']=base['feedTemperatureKelvin']*u()
  inp['topPressurePascal']=min(290_000.0,base['topPressurePascal']*u());inp['stagePressureDropPascal']=min(500.0,(300_000.0-inp['topPressurePascal'])/(n-1))*rng.random()
  spec=base['specifications'];inp['specifications']=[{'kelvin':max(298.15,spec[0]['kelvin']*(0.95+0.10*rng.random()))},{'ratio':spec[1]['ratio']*u()},{'watts':spec[2]['watts']}]
  trays=set();draws=[]
  for s in base['sideDraws']:
   t=scale(s['trayNumber'])
   while t in trays:t=t%n+1
   trays.add(t);draws.append({'trayNumber':t,'molarFlowMolPerSecond':s['molarFlowMolPerSecond']*u()})
  inp['sideDraws']=sorted(draws,key=lambda x:x['trayNumber'])
  inp['steamFeeds']=[{'stageNumber':n+1,'molarFlowMolPerSecond':s['molarFlowMolPerSecond']*u(),'temperatureKelvin':s['temperatureKelvin']*(0.95+0.10*rng.random())} for s in base['steamFeeds']]
  pairs=set();pas=[]
  for p in base['pumparounds']:
   r=scale(p['returnTray']);w=max(r,min(n,r+(p['drawTray']-p['returnTray'])+rng.choice((-1,0,1))))
   while (r,w) in pairs:w=min(n,w+1);r=r if w<n else max(1,r-1)
   pairs.add((r,w));pas.append({'returnTray':r,'drawTray':w,'dutyWatts':p['dutyWatts']*u(),'split':'UNIFORM'})
  inp['pumparounds']=sorted(pas,key=lambda x:(x['returnTray'],x['drawTray']))
  if not d.request_only_exclusions(inp,0.30):return inp,blend,attempt
 raise RuntimeError('no admissible neighbourhood draw')

def build(seed,prefix,split,replicates,near,stages):
 rng=random.Random(seed);rows=[];redraws=0
 for k,preset in enumerate(presets):
  i=0
  for steam in (False,True):
   for pas in range(5):
    for draws in range(4):
     for rep in range(replicates):
      n=rng.choice(stages);inp,blend,attempt=factorial(rng,k,n,steam,pas,draws);redraws+=attempt
      rows.append({'id':f'{prefix}-{preset["id"]}-w{int(steam)}p{pas}d{draws}-{rep:02d}','split':split,'design':{'family':'factorial','source':preset['id'],'seed':seed,'blend':blend},'input':inp})
  for rep in range(near):
   n=rng.choice(NEAR_STAGES if split=='train' else [36,37,38,39,40,41,42,43,44]);inp,blend,attempt=neighbourhood(rng,k,n);redraws+=attempt
   rows.append({'id':f'{prefix}-{preset["id"]}-near-{rep:03d}','split':split,'design':{'family':'neighbourhood','source':preset['id'],'seed':seed,'blend':blend},'input':inp})
 for r in rows:
  h=d.digest(r['input']);assert h not in existing,r['id'];existing.add(h);r['design']['inputSha256']=h
  assert r['input']['componentBasis']['componentIds']==presets[0]['input']['componentBasis']['componentIds']
  if split=='train':assert d.split_for(r['input']['stageCount'])=='train',r['id']
 rng.shuffle(rows);return rows,redraws

if __name__=='__main__':
 OUT.mkdir(exist_ok=True);manifest={}
 for name,args in {'train-v2':(2026091811,'v2','train',20,100,TRAIN_STAGES),'holdout-v2':(2026091823,'h2','fresh-holdout-v2',1,12,list(range(2,65)))}.items():
  rows,redraws=build(*args);path=OUT/f'{name}.jsonl';assert not path.exists();path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
  manifest[name]={'cases':len(rows),'seed':args[0],'screenRedraws':redraws,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
  if name.startswith('holdout'):
   (OUT/f'{name}-reverse.jsonl').write_text(''.join(json.dumps({**r,'reverseOrder':True})+'\n' for r in reversed(rows)))
 manifest['generatorSha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
 (OUT/'registration.json').write_text(json.dumps(manifest,indent=2));print(json.dumps(manifest,indent=2))

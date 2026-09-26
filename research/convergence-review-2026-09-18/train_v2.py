"""Scratch retraining on original + corrected-design labels. Same architecture, loss, optimiser, seeds and
checkpoints as research/crude-regrouping/training/train.py; only the label set and the envelope source change.
usage: train_v2.py <arm-name> <cases.jsonl|features.jsonl> [more sources...]"""
import os
os.environ['CUBLAS_WORKSPACE_CONFIG']=':4096:8';os.environ['OMP_NUM_THREADS']='1'
import sys,json,hashlib,time
from pathlib import Path
import numpy as np
import torch
from torch import nn
ROOT=Path(__file__).resolve().parents[2];HERE=Path(__file__).resolve().parent;T=ROOT/'research/crude-regrouping/training'
sys.path.insert(0,str(ROOT/'tools/neural'))
import train_transformer as old
import train_generalized as base
import train_gen3_factorized as factor
from prepare_transformer_data import strict
arm=sys.argv[1];sources=[tuple(Path(p) for p in a.split('|')) for a in sys.argv[2:]]
OUT=HERE/'models'/arm;OUT.mkdir(parents=True,exist_ok=False)
BASIS=json.loads((T/'basis.json').read_text());read=lambda p:[json.loads(s) for s in p.read_text(encoding='utf-8').splitlines() if s]
rows=[];feature={}
for cases,features in sources:
 rows+=read(cases);feature.update({r['id']:r for r in read(features)})
train=[r for r in rows if r['split']=='train' and strict(r)];valid=[r for r in rows if r['split']=='validation' and strict(r)]
assert train and valid and len({r['design']['inputSha256'] for r in train})==len(train)
for r in train+valid:
 f=feature[r['id']]
 np.testing.assert_allclose(f['g'],base.global_features(r['input']),rtol=1e-12,atol=1e-12)
 np.testing.assert_allclose(f['y'],factor.targets(r)[0],rtol=1e-10,atol=1e-10)
assert all(r['input']['componentBasis']['componentIds']==BASIS['components'] for r in train+valid)
c=len(BASIS['components']);out=BASIS['outputWidth'];norm={}
for prefix,key in [('x','x'),('y','y')]:
 arrays=[np.asarray(feature[r['id']][key]) for r in train];weights=np.concatenate([np.full(len(a),1/len(a)) for a in arrays])
 norm[prefix+'m'],norm[prefix+'scale']=base.moments(np.vstack(arrays),weights)
norm['gm'],norm['gscale']=base.moments(np.array([feature[r['id']]['g'] for r in train]))
anchors=[np.asarray(a['values']) for r in train for a in feature[r['id']]['anchors'] if a['available']]
norm['bm'],norm['bscale']=base.moments(np.vstack(anchors),np.concatenate([np.full(len(a),1/len(a)) for a in anchors]))
norm['yscale'][0]=max(norm['yscale'][0],25);norm['yscale'][1:3]=np.maximum(norm['yscale'][1:3],.1)
norm['yscale'][3:3+2*c]=np.maximum(norm['yscale'][3:3+2*c],1);norm['yscale'][3+2*c]=max(norm['yscale'][3+2*c],1)
norm['yscale'][4+2*c]=1;norm['ym'][5+2*c:]=0;norm['yscale'][5+2*c:]=1
seen=np.array([any(r['seed']['branch']==b for r in train) for b in base.BRANCHES])
device=torch.device('cuda');torch.set_num_threads(1);torch.use_deterministic_algorithms(True)
torch.backends.cuda.matmul.allow_tf32=False;torch.backends.cudnn.allow_tf32=False

def tensors(data):
 n=len(data);length=max(r['input']['stageCount']+2 for r in data)
 arrays={'x':np.zeros((n,length,76+c)),'g':np.zeros((n,54+c)),'anchors':np.zeros((n,3,length,out)),'available':np.zeros((n,3,length,1)),'raw':np.zeros((n,length,out)),'fractions':np.zeros((n,length,2,c)),'valid':np.zeros((n,length),bool),'feed':np.zeros((n,c)),'labels':np.zeros(n,np.int64),'legal':np.tile(seen,(n,1))}
 for i,r in enumerate(data):
  f=feature[r['id']];m=len(f['x']);arrays['valid'][i,:m]=True
  arrays['x'][i,:m]=(f['x']-norm['xm'])/norm['xscale'];arrays['g'][i]=(f['g']-norm['gm'])/norm['gscale']
  arrays['raw'][i,:m]=f['y'];arrays['fractions'][i,:m]=factor.targets(r)[1]
  z=np.array(r['input']['feedComponentMolarFlowsMolPerSecond']);arrays['feed'][i]=z/z.sum();arrays['labels'][i]=base.BRANCHES.index(r['seed']['branch'])
  if any(s.get('ratio',0)>0 for s in r['input']['specifications']):arrays['legal'][i,2]=False
  for j,a in enumerate(f['anchors']):
   arrays['anchors'][i,j,:m]=(a['values']-norm['bm'])/norm['bscale'];arrays['available'][i,j,:m]=float(a['available'])
 return {k:torch.as_tensor(v,device=device,dtype=torch.bool if k in ('valid','legal') else torch.long if k=='labels' else torch.float32) for k,v in arrays.items()}

class Model(old.ColumnModel):
 def __init__(self):
  super().__init__('transformer',76+c+out+4,out);self.branch[0]=nn.Linear(54+c,64)
 def forward_batch(self,b):
  logits=self.branch(b['g']);choice=logits.masked_fill(~b['legal'],-torch.inf).argmax(-1);idx=torch.arange(len(choice),device=choice.device)
  onehot=torch.nn.functional.one_hot(choice,3).to(b['x'].dtype)[:,None,:].expand(-1,b['x'].shape[1],-1)
  joined=torch.cat([b['x'],b['anchors'][idx,choice],onehot,b['available'][idx,choice]],-1)
  raw,_=super().forward(joined,b['g'],b['valid']);return raw,logits

training=tensors(train);validating=tensors(valid);ym=torch.tensor(norm['ym'],device=device,dtype=torch.float32);ys=torch.tensor(norm['yscale'],device=device,dtype=torch.float32)
# Envelope = authored input domain of every registered request file (inputs only).
domain=[T/'requests.jsonl',T/'holdout-fixed.jsonl',HERE/'design-v2/train-v2.jsonl',HERE/'design-v2/holdout-v2.jsonl']
if os.environ.get('GLOBAL_DOMAIN')=='1':domain+=[HERE/'design-g/train-g.jsonl',HERE/'design-g/holdout-g.jsonl']
globals_=np.array([base.global_features(r['input']) for p in domain for r in read(p)]);presets=json.loads((T/'presets.json').read_text())
z=[np.array(p['input']['feedComponentMolarFlowsMolPerSecond']) for p in presets];z=[a/a.sum() for a in z]
(OUT/'training-data-audit.json').write_text(json.dumps({'arm':arm,'counts':{'train':len(train),'validation':len(valid),'all':len(rows)},'branchesSeen':seen.tolist(),'torch':torch.__version__,'gpu':torch.cuda.get_device_name(),'sources':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for pair in sources for p in pair}},indent=2))

def export(model,dest):
 weights={k:{'shape':list(v.shape),'values':v.detach().cpu().double().numpy().flatten().tolist()} for k,v in model.state_dict().items()}
 payload={'schemaVersion':1,'featureRevision':BASIS['featureRevision'],'modelType':'anchor-augmented','anchorLayout':'full','baselineRevision':BASIS['anchorRevision'],'components':BASIS['components'],'normalization':{k:v.tolist() for k,v in norm.items()},'weights':weights}
 encoded=json.dumps(payload,separators=(',',':'),allow_nan=False).encode();dest.with_suffix('.payload.json').write_bytes(encoded)
 side={'schemaVersion':2,'payloadSha256':hashlib.sha256(encoded).hexdigest(),'modelId':f'regrouped-{arm}-'+dest.parent.name+'-'+dest.name,'packageId':presets[0]['input']['packageId'],'propertyRevision':presets[0]['propertyRevision'],'physicsFingerprint':BASIS['physicsFingerprint'],'allowExtraZeroComponents':False,'allowMissingZeroComponents':False,'formulationRevisions':sorted({r['formulationRevision'] for r in train}),'branchesSeen':seen.tolist(),'presenceThreshold':.02,'traceFloorFraction':1e-10,'globalMin':globals_.min(0).tolist(),'globalMax':globals_.max(0).tolist(),'compositionEdges':[[z[i].tolist(),z[(i+1)%len(z)].tolist()] for i in range(len(z))],'designConstraints':{'minimumNodePressurePascal':50000,'maximumNodePressurePascal':300000,'steamAtSumpOnly':True,'pumparoundSplits':['UNIFORM']},'decoder':'ZERO_PHASE_FLOOR_10','candidateRule':'SINGLE','correctionRule':'PROGRESS'}
 dest.with_suffix('.sidecar.json').write_text(json.dumps(side,separators=(',',':'),allow_nan=False));torch.save(model.state_dict(),dest.with_suffix('.pt'))

for seed in [17011,17023,17041]:
 dest=OUT/f'seed-{seed}';dest.mkdir();torch.manual_seed(seed);torch.cuda.manual_seed_all(seed);np.random.seed(seed)
 model=Model().to(device);assert sum(p.numel() for p in model.parameters())==67395+BASIS['joinedWidth']*64+BASIS['outputWidth']*65+BASIS['globalWidth']*64
 opt=torch.optim.AdamW(model.parameters(),lr=8e-4,weight_decay=1e-4);history=[];start=time.perf_counter()
 for epoch in range(1,161):
  if os.environ.get('LR_STEP')=='1' and epoch==121:
   for group in opt.param_groups:group['lr']=8e-5
  model.train();total=0
  for ix in torch.randperm(len(train),device=device).split(32):
   batch=old.subset(training,ix);opt.zero_grad(set_to_none=True);pred,logits=model.forward_batch(batch)
   loss=old.loss(pred,logits,batch,ym,ys);assert torch.isfinite(loss);loss.backward();nn.utils.clip_grad_norm_(model.parameters(),1.);opt.step();total+=loss.item()*len(ix)
  model.eval()
  with torch.no_grad():pred,logits=model.forward_batch(validating);vl=old.loss(pred,logits,validating,ym,ys).item()
  history.append({'epoch':epoch,'trainLoss':total/len(train),'validationLoss':vl})
  if epoch%20==0:print(arm,seed,epoch,history[-1],round(time.perf_counter()-start,1),flush=True)
  if epoch in (80,160):export(model,dest/f'epoch-{epoch}')
 (dest/'history.json').write_text(json.dumps(history,indent=2))
print('done; nothing promoted',flush=True)

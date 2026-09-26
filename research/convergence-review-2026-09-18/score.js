// usage: node score.js <run-dir> [<run-dir> ...]   (each holds evaluation.jsonl from compare mode)
const fs=require('fs');
const strict=m=>m&&m.success&&(m.waterQualification==='DRY_EQUILIBRIUM'||m.waterQualification==='WET_EQUILIBRIUM');
function cls(r){const i=r.input;
 if(i.steamFeeds.length===0&&(i.specifications[2].watts||0)===0&&i.feedStageNumber<i.stageCount)return 'A zero-boilup';
 const f=String(r.current.failure||'');
 if(/condensation-capped|not below the/.test(f))return 'B heat gate';
 if(r.current.status==='INFEASIBLE_SPECIFICATION')return 'C typed infeasible';
 return 'D open';}
const out={};
for(const dir of process.argv.slice(2)){
 const rows=fs.readFileSync(dir+'/evaluation.jsonl','utf8').split('\n').filter(Boolean).map(JSON.parse);
 const meta=JSON.parse(fs.readFileSync(dir+'/run.json','utf8'));
 const sets={current:new Set(),neural:new Set(),neuralFirst:new Set()};const ms={current:0,neural:0,neuralFirst:0};
 const byClass={};const accepted={current:0,neural:0,neuralFirst:0};
 for(const r of rows){const k=cls(r);byClass[k]=byClass[k]||{n:0,current:0,neural:0,neuralFirst:0};byClass[k].n++;
  for(const m of Object.keys(sets)){if(strict(r[m])){sets[m].add(r.id);byClass[k][m]++;}if(r[m].success)accepted[m]++;ms[m]+=r[m].ms;}}
 const n=rows.length;
 console.log('\n== '+dir+'  model='+meta.modelId+'  n='+n);
 console.log('strict   classical',sets.current.size,' neural-only',sets.neural.size,' neural-first',sets.neuralFirst.size,'  (accepted incl. advisories:',accepted.current,accepted.neural,accepted.neuralFirst+')');
 console.log('mean ms  classical',(ms.current/n).toFixed(0),' neural-only',(ms.neural/n).toFixed(0),' neural-first',(ms.neuralFirst/n).toFixed(0),' ratio first/classical',(ms.neuralFirst/ms.current).toFixed(3));
 console.log('classical strict lost under neural-first:',[...sets.current].filter(x=>!sets.neuralFirst.has(x)).length);
 for(const [k,e] of Object.entries(byClass).sort())console.log('  '+k.padEnd(20),'n',String(e.n).padStart(4),'classical',String(e.current).padStart(4),'only',String(e.neural).padStart(4),'first',String(e.neuralFirst).padStart(4));
 const presets=rows.filter(r=>/-production$/.test(r.id));
 for(const r of presets)console.log('  preset',r.id.padEnd(28),'classical',r.current.status,Math.round(r.current.ms)+'ms','| only',r.neural.status,Math.round(r.neural.ms)+'ms','| first',r.neuralFirst.status,Math.round(r.neuralFirst.ms)+'ms');
 const near=rows.filter(r=>r.design&&r.design.family&&r.design.family.startsWith('neighbourhood'));
 if(near.length){const c=m=>near.filter(r=>strict(r[m])).length,a=m=>near.filter(r=>r[m].success).length,t=m=>near.reduce((s,r)=>s+r[m].ms,0)/near.length;
  console.log('  preset neighbourhood n',near.length,'strict classical/only/first',c('current'),c('neural'),c('neuralFirst'),' accepted',a('current'),a('neural'),a('neuralFirst'),' mean ms',t('current').toFixed(0),t('neural').toFixed(0),t('neuralFirst').toFixed(0));}
 out[dir]={model:meta.modelId,n,strict:{current:sets.current.size,neural:sets.neural.size,neuralFirst:sets.neuralFirst.size},meanMs:{current:ms.current/n,neural:ms.neural/n,neuralFirst:ms.neuralFirst/n},byClass};
}
if(process.env.SCORE_JSON)fs.writeFileSync(process.env.SCORE_JSON,JSON.stringify(out,null,1));

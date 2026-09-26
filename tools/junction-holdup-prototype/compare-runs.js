// Compare the HOLDUP_* lines of two transient probe XML files, case by case (review 7.9; generalised for 7.10).
// Usage: node compare-runs.js <a-transient.xml> <b-transient.xml> [mRefA] [mRefB]
// The junction mass reference per case is the HOLDUP_MINT line's mJ when the file has one (runs 67+),
// else mRefA / mRefB (kg), else 1e-4 kg. Substep sums are over the printed trajectory lines; when the
// HOLDUP_METRICS line carries accepted=/rejected= (runs 67+) the all-interval totals are shown too.
const fs=require('fs');
function parse(file){
  const text=fs.readFileSync(file,'utf8');const cases={};
  for(const line of text.split(/\r?\n/)){
    const m=line.match(/(HOLDUP_[A-Z]+) .*?case=(\S+)/);if(!m)continue;
    const c=cases[m[2]]??={traj:[],dyn:null,met:null,mint:null,tot:null};
    if(m[1]==='HOLDUP_TRAJECTORY'){
      const t=+line.match(/time=(\S+)/)[1];const mJ=+line.match(/mJ=(\S+)/)[1];
      const sub=line.match(/substeps=(\d+)\/(\d+)/);const wJ=+line.match(/wJ=(\S+)/)[1];const wMix=+line.match(/wMix=(\S+)/)[1];
      const body=line.slice(line.indexOf('time=')).replace(/substeps=\S+ /,'');
      c.traj.push({t,mJ,acc:+sub[1],rej:+sub[2],wJ,wMix,body});
    }else if(m[1]==='HOLDUP_DYNAMIC'){c.dyn=line.slice(line.indexOf('case=')).replace(/^case=\S+ /,'');}
    else if(m[1]==='HOLDUP_MINT'){c.mint=+line.match(/mJ=(\S+)/)[1];}
    else if(m[1]==='HOLDUP_METRICS'){const a=line.match(/maxComponentError=(\S+) maxEnergyError=(\S+)/);c.met=[+a[1],+a[2]];
      const t=line.match(/accepted=(\d+) rejected=(\d+)/);if(t)c.tot=[+t[1],+t[2]];}
  }
  return cases;
}
const A=parse(process.argv[2]),B=parse(process.argv[3]);
const fmt=x=>x===undefined||x===null||isNaN(x)?'-':x.toExponential(2);
console.log('| Case | A verdict | B verdict | A metrics comp/energy | B metrics | A mJ(13)/ref-1 | A mJ(16)/ref-1 | B mJ(13)/ref-1 | B mJ(16)/ref-1 | A subst acc/rej (printed; all) | B subst | first differing line |');
console.log('|---|---|---|---|---|---|---|---|---|---|---|---|');
for(const k of Object.keys(A)){
  const a=A[k],b=B[k]??{traj:[]};
  const refA=a.mint??(+process.argv[4]||1e-4),refB=b.mint??(+process.argv[5]||1e-4);
  const g=(x,t)=>{const r=x.traj.find(r=>Math.abs(r.t-t)<1e-9);return r?r.mJ:NaN;};
  const sum=x=>x.traj.reduce((s,r)=>[s[0]+r.acc,s[1]+r.rej],[0,0]).join('/')+(x.tot?'; '+x.tot.join('/'):'');
  let diff='none';for(let i=0;i<Math.min(a.traj.length,b.traj.length);i++)if(a.traj[i].body!==b.traj[i].body){diff='t='+a.traj[i].t;break;}
  const v=x=>x?(x.startsWith('PASS')?'PASS '+x.match(/ms=(\d+)/)[1]+' ms':x.slice(0,60)):'-';
  const rel=(x,ref,t)=>(g(x,t)-ref)/ref;
  console.log(`| ${k} | ${v(a.dyn)} | ${v(b.dyn)} | ${a.met?fmt(a.met[0])+' / '+fmt(a.met[1]):'-'} | ${b.met?fmt(b.met[0])+' / '+fmt(b.met[1]):'-'} | ${fmt(rel(a,refA,13))} | ${fmt(rel(a,refA,16))} | ${fmt(rel(b,refB,13))} | ${fmt(rel(b,refB,16))} | ${sum(a)} | ${sum(b)} | ${diff} |`);
}
// |wJ - wMix| per case at t = 1, 10 and 16
console.log('\n| Case | A |wJ-wMix| t=1 | B t=1 | A t=10 | B t=10 | A t=16 | B t=16 |');
console.log('|---|---|---|---|---|---|---|');
for(const k of Object.keys(A)){const a=A[k],b=B[k]??{traj:[]};const w=(x,t)=>{const r=x.traj.find(r=>Math.abs(r.t-t)<1e-9);return r?Math.abs(r.wJ-r.wMix):NaN;};
  console.log(`| ${k} | ${fmt(w(a,1))} | ${fmt(w(b,1))} | ${fmt(w(a,10))} | ${fmt(w(b,10))} | ${fmt(w(a,16))} | ${fmt(w(b,16))} |`);}

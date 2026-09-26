// Junction mass budget (review 7.9): from a transient XML made with -PstageFormTrace=true, print per case the
// accepted junction mass change split into the endpoint-rate term, stage one and stage two, at t = 10, 13, 16 s,
// relative to m_J = 1e-4 kg, and check that the trace is print-only against a reference XML without the trace.
// Usage: node budget.js <traced.xml> [<reference.xml>]
const fs=require('fs');
const text=fs.readFileSync(process.argv[2],'utf8');
const ref=process.argv[3]?fs.readFileSync(process.argv[3],'utf8'):null;
const lines=l=>l.split(/\r?\n/).filter(x=>x.includes('HOLDUP_TRAJECTORY')).map(x=>x.slice(x.indexOf('HOLDUP_TRAJECTORY')));
const traced=lines(text);
if(ref){const r=lines(ref);let same=r.length===traced.length;for(let i=0;same&&i<r.length;i++)if(traced[i].replace(/ budget=\[.*\]$/,'')!==r[i])same=false;console.log('trajectory lines identical to reference apart from the budget:',same);}
const cases={};
for(const l of traced){const c=l.match(/case=(\S+)/)[1],t=+l.match(/time=(\S+)/)[1],mJ=+l.match(/mJ=(\S+)/)[1];const b=l.match(/budget=\[([^\]]+)\]/);if(!b)continue;(cases[c]??=[]).push({t,mJ,b:b[1].split(',').map(Number)});}
const f=x=>x.toExponential(2);
console.log('| Case | t | mJ/1e-4 - 1 | rate | stage 1 | stage 2 | sum - (mJ - 1e-4) |');console.log('|---|---|---|---|---|---|---|');
for(const [c,rows] of Object.entries(cases))for(const t of [10,13,16]){const r=rows.find(x=>Math.abs(x.t-t)<1e-9);if(!r)continue;const s=r.b[0]+r.b[1]+r.b[2];
  console.log(`| ${c} | ${t} | ${f(r.mJ/1e-4-1)} | ${f(r.b[0]/1e-4)} | ${f(r.b[1]/1e-4)} | ${f(r.b[2]/1e-4)} | ${f(s-(r.mJ-1e-4))} |`);}

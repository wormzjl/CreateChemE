// Review 8.7 (runs 91+): totals of the HOLDUP_COST lines of probe XMLs, with the reuse, flash, timer and allocation fields.
// Usage: node cost-wide.js [--cases] label=file.xml[,file2.xml] ...   (--cases: one row per case of the first run too)
const fs=require('fs');
const args=process.argv.slice(2);const perCase=args[0]==='--cases';if(perCase)args.shift();
function parse(line){const r={case:/case=(\S+)/.exec(line)[1]};
 for(const m of line.matchAll(/ ([A-Za-z]+)=([0-9.E+-]+)(?= |$)/g))r[m[1]]=Number(m[2]);
 const a=/threadAllocMB=([0-9.]+)/.exec(line);r.allocMB=a?Number(a[1]):0;
 r.rej={};const b=/rejectedBy=\{([^}]*)\}/.exec(line);if(b&&b[1].trim())for(const kv of b[1].split(', ')){const [k,v]=kv.split('=');if(Number(v))r.rej[k]=Number(v);}
 return r;}
function rows(files){let out=[];for(const f of files.split(','))out=out.concat(fs.readFileSync(f,'utf8').split(/\r?\n/).filter(l=>l.startsWith('HOLDUP_COST ')).map(parse));return out;}
function sum(rs,label){const t={case:label,rej:{}};for(const r of rs)for(const [k,v] of Object.entries(r)){if(k==='case')continue;if(k==='rej'){for(const [kk,vv] of Object.entries(v))t.rej[kk]=(t.rej[kk]||0)+vv;continue;}t[k]=(t[k]||0)+v;}t.cases=rs.length;return t;}
const g=(r,k)=>r[k]||0;const f1=x=>x.toFixed(1),f2=x=>x.toFixed(2),mb=x=>(x/1048576).toFixed(1);
function row(r){const rejected=Object.values(r.rej).reduce((a,b)=>a+b,0);const s=g(r,'newtonSolves'),it=g(r,'newtonIterations');
 const flashes=g(r,'flashNewton')+g(r,'flashReconstructed')+g(r,'flashFailed')+g(r,'flashTraceSeed');
 return `| ${r.case} | ${g(r,'ms')} | ${g(r,'accepted')} | ${rejected}${rejected?' ('+Object.entries(r.rej).map(([k,v])=>k+' '+v).join(', ')+')':''} | ${s} | ${f2(it/Math.max(1,s))} | ${g(r,'jacobianBuilds')} | ${g(r,'newtonSolvesPreconditioned')} | ${g(r,'residualEvaluations')} | ${g(r,'stateCalls')} | ${g(r,'flashCalls')} (${g(r,'flashNewton')}/${g(r,'flashReconstructed')}/${g(r,'flashFailed')}/${g(r,'flashTraceSeed')}/${g(r,'seedFlashes')}) | ${f1(g(r,'flashNanos')/1e6)} | ${f1(g(r,'seedNanos')/1e6)} / ${f1(g(r,'coldRateNanos')/1e6)} | ${f1(g(r,'jacobianNanos')/1e6)} | ${f1(g(r,'reconstructNanos')/1e6)} | ${f1(g(r,'allocMB'))} | ${mb(g(r,'newtonAllocBytes'))} (${mb(g(r,'jacobianAllocBytes'))}) | ${mb(g(r,'phaseAllocBytes'))} | ${mb(g(r,'reconstructAllocBytes'))} | ${mb(g(r,'conservationAllocBytes'))} | ${g(r,'predictedSolves')} |`;}
console.log('| run / case | ms | accepted | rejected | Newton solves | iter/solve | Jacobians | solves opened preconditioned | residual evals | state calls | flashes (Newton-check/reconstructed-check/failed-pass/trace-seed/cold-seed) | flash ms (checks + trace seeds) | cold seed ms / cold rate-solve ms | Jacobian ms | reconstruct ms | alloc MB | Newton MB (Jacobian MB) | phase-check MB | reconstruct MB | conservation MB | predicted solves |');
console.log('|'+'---|'.repeat(21));
let first=true;
for(const a of args){const [label,files]=a.split('=');const rs=rows(files);
 if(perCase&&first)for(const r of rs)console.log(row(r));first=false;
 const tr=rs.filter(r=>!r.case.startsWith('static'));const st=rs.filter(r=>r.case.startsWith('static'));
 if(tr.length)console.log(row(sum(tr,label+' transient ('+tr.length+')')));
 if(st.length)console.log(row(sum(st,label+' static ('+st.length+')')));}

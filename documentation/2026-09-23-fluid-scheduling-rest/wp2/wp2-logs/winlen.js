const path=require('path'); const R=process.argv[2]; const runs=process.argv.slice(3);
const pct=(a,p)=>{if(!a.length)return null;const s=[...a].sort((x,y)=>x-y);return s[Math.min(s.length-1,Math.floor(p*s.length))];};
const f=v=>v==null?'-':(typeof v==='number'?(Number.isInteger(v)?String(v):v.toFixed(v<1?4:2)):String(v));
const W=[15,30,45,60,90,120];
for (const run of runs){
  let r; try{r=require(path.join(R,run,'report.json'));}catch(e){console.log('missing',run);continue;}
  const w0=r.warmupTicks; const s=r.samples||[]; const raw=r.rawEngineMillisecondsPerTick||[]; const ss=r.stressSamples||[];
  console.log('\n### '+run+' warmupTicks='+w0+' measured='+(r.measuredSeconds||0).toFixed(1)+'s publications='+s.length+' rawTicks='+raw.length);
  console.log('| window s | pubs | r2p p50 | r2p p95 | worker p50 | worker p95 | engine p50 | engine p95 | certified@t | workerLimit@t | eligible mean |');
  for (const T of W){
    const sub=s.filter(x=>x.timing&&x.timing.publishedAtTick-w0<T*20);
    const r2p=sub.map(x=>x.readyToPublicationMillis); const wk=sub.map(x=>x.timing.workerNanos/1e6);
    const e=raw.slice(0,T*20);
    const st=ss[Math.min(ss.length-1,T-1)]; const el=ss.slice(0,T).map(x=>x.eligibleIslands); const elm=el.length?el.reduce((a,b)=>a+b,0)/el.length:null;
    console.log(`| ${T} | ${sub.length} | ${f(pct(r2p,.5))} | ${f(pct(r2p,.95))} | ${f(pct(wk,.5))} | ${f(pct(wk,.95))} | ${f(pct(e,.5))} | ${f(pct(e,.95))} | ${st?st.certifiedIslands:'-'} | ${st?st.workerLimit:'-'} | ${f(elm)} |`);
  }
  const wu=r.warmupSamples||[]; if(wu.length&&wu[0].timing){
    const bins={}; for(const x of wu){const b=Math.floor(x.timing.publishedAtTick/200)*10; (bins[b]=bins[b]||[]).push(x.readyToPublicationMillis);}
    console.log('warm-up per 10 s: '+Object.keys(bins).sort((a,b)=>a-b).map(b=>`${b}-${+b+10}s n=${bins[b].length} p50=${f(pct(bins[b],.5))} p95=${f(pct(bins[b],.95))}`).join(' | '));
    console.log('held: warm-up '+JSON.stringify(r.warmupHeldIntervals)+' measured '+JSON.stringify(r.heldIntervals));
  }
}

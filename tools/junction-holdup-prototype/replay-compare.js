// Review 8.9: node replay-compare.js <test.xml> [island ids] - splits the REPLAYJOB/REPLAYTRACE stream of
// CausalModuleCoordinatorTest into harness runs (a new harness starts when node 1's job comes from a retained solver not
// seen before and four have been seen), and prints, per island, the first job whose trace differs between run 0
// (reference: every interval solved) and run 1 (certified), with both jobs' lines.
const fs=require('fs');const xml=fs.readFileSync(process.argv[2],'utf8');
const want=(process.argv[3]||'1,2,3,4').split(',');
const lines=xml.split(/\r?\n/).map(l=>l.replace(/^.*?(REPLAY(JOB|TRACE))/,'$1')).filter(l=>/^REPLAY(JOB|TRACE)/.test(l));
const runs=[];let seen=new Set(),cur=null,job=null;
for(const l of lines){
  if(l.startsWith('REPLAYJOB')){
    const ret=/retained=(\w+)/.exec(l)[1],node=/node0=(\d+)/.exec(l)[1];
    if(!cur||(node==='1'&&!seen.has(ret)&&seen.size>=4&&cur.fresh.size>=4)){cur={jobs:{},fresh:new Set()};runs.push(cur);seen=new Set();}
    if(!seen.has(ret)){seen.add(ret);cur.fresh.add(ret);}
    job={head:l,node,lines:[],newRetained:false};(cur.jobs[node]=cur.jobs[node]||[]).push(job);
  } else if(job)job.lines.push(l);
}
console.log('harness runs: '+runs.length+'; jobs per island: '+runs.map(r=>Object.entries(r.jobs).map(([k,v])=>k+':'+v.length).join(' ')).join(' | '));
const norm=l=>l.replace(/solver=\w+/,'solver=*').replace(/retained=\w+/,'retained=*');
const strip=l=>norm(l).replace(/ workspaces=\d+ structures=\d+ previousFlows=\d+ previousModes=\d+/,'');
for(const id of want){
  const a=(runs[0]&&runs[0].jobs[id])||[],b=(runs[1]&&runs[1].jobs[id])||[];
  // The certified run skips rested intervals: align on jobs that carry transfers (trials) and on the trace of the state.
  const ta=a.filter(j=>j.head.includes('trial')),tb=b.filter(j=>j.head.includes('trial'));
  let found=false;
  for(let i=0;i<Math.min(ta.length,tb.length);i++){
    const la=ta[i].lines.map(strip),lb=tb[i].lines.map(strip);
    let k=0;while(k<la.length&&k<lb.length&&la[k]===lb[k])k++;
    if(k<la.length||k<lb.length){found=true;
      console.log(`island ${id}: first differing trial job #${i} (of ${ta.length}/${tb.length}), line ${k}`);
      const pa=a.indexOf(ta[i]),pb=b.indexOf(tb[i]);
      console.log(' reference previous job: '+(pa>0?norm(a[pa-1].head)+' / '+a[pa-1].lines.slice(-1)[0]:'-'));
      console.log(' certified previous job: '+(pb>0?norm(b[pb-1].head)+' / '+b[pb-1].lines.slice(-1)[0]:'-'));
      for(let m=Math.max(0,k-1);m<Math.min(k+2,Math.max(la.length,lb.length));m++){console.log(' ref  '+(ta[i].lines[m]||'').slice(0,330));console.log(' cert '+(tb[i].lines[m]||'').slice(0,330));}
      break;}
  }
  if(!found)console.log(`island ${id}: ${ta.length}/${tb.length} trial jobs, all traces equal`);
}

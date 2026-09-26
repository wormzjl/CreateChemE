// Review 8.8: read JunctionEventProbe XML. node events.js <xml> [case[,case]] [every] [cadence]
// Prints, per case/cadence/reopen: the first slice, every slice whose modes or (non-empty) reasons changed, every
// `every`-th slice, the held and summary lines.
const fs=require('fs');
const s=fs.readFileSync(process.argv[2],'utf8').replace(/&#10;/g,'\n').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&').replace(/&quot;/g,'"');
const want=(process.argv[3]||'').split(',').filter(x=>x);const every=Number(process.argv[4]||0);const cadence=process.argv[5]||'';
const runs={};
for(const line of s.split(/\r?\n/)){
  const m=line.match(/^(EVENT|EVENT_HELD|EVENT_SUMMARY) case=(\S+) cadence=(\S+) reopen=(\S+)/);if(!m)continue;
  if(want.length&&!want.includes(m[2]))continue;
  if(cadence&&m[3]!==cadence)continue;
  const key=m[2]+' '+m[3]+' '+m[4];(runs[key]=runs[key]||[]).push(line);
}
function field(l,k){
  const at=l.indexOf(' '+k+'=');if(at<0)return undefined;
  let i=at+k.length+2;const open=l[i];
  if(open==='['||open==='{'){const close=open==='['?']':'}';let depth=0,j=i;for(;j<l.length;j++){if(l[j]===open)depth++;else if(l[j]===close){depth--;if(depth===0)break;}}return l.slice(i,j+1);}
  let j=i;while(j<l.length&&l[j]!==' ')j++;return l.slice(i,j);
}
for(const [key,lines] of Object.entries(runs)){
  console.log('== '+key);let prevModes=null,prevReasons=null;
  lines.forEach((l,i)=>{
    if(!l.startsWith('EVENT ')){console.log('  '+l.slice(0,900));return;}
    const modes=field(l,'modes'),reasons=field(l,'reasons');const slice=Number(field(l,'slice'));
    const show=modes!==prevModes||(every&&slice%every===0)||i===0||(reasons!==prevReasons&&reasons!=='{}');
    if(show)console.log('  slice='+slice+' t='+field(l,'t')+' acc='+field(l,'acc')+' rej='+field(l,'rej')+' modes='+modes+' qEnd='+field(l,'qEnd')+' cap='+field(l,'capRatio')+' ledger='+field(l,'ledger')
      +(l.match(/ tank\d+\([^)]*\)/g)||[]).join('')+(l.match(/ (cakeKg|loading|stopped|blocked|dP|limitNow|heads|T1)=\S+/g)||[]).join('')+' reasons='+(reasons||'').slice(0,200));
    prevModes=modes;prevReasons=reasons;
  });
}

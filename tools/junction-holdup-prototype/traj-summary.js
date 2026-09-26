// Review 8.7 Step 1: largest tank-pressure difference to a reference run at given times, per bore, and the reference's
// remaining gauge (P - 101325 Pa) there. Usage: node traj-summary.js <times> ref=<xml> label=<xml> ...
const fs=require('fs');const [, , times, ...runs]=process.argv;const want=times.split(',');
const read=f=>{const d={};for(const l of fs.readFileSync(f,'utf8').split(/\r?\n/)){const m=/HOLDUP_TRAJECTORY case=(\S+) time=(\S+) P=\[([^\]]*)\]/.exec(l);if(m&&want.includes(m[2]))d[m[1]+'@'+m[2]]=m[3].split(', ').map(Number);}return d;};
const [refLabel,refFile]=runs[0].split('=');const ref=read(refFile);
console.log('| run | bore | '+want.map(t=>'max |dP| @ '+t+' s (Pa) [ref gauge range]').join(' | ')+' |');console.log('|'+'---|'.repeat(2+want.length));
for(const r of runs.slice(1)){const [label,file]=r.split('=');const d=read(file);
 for(const bore of ['0.05','0.02']){const cells=want.map(t=>{let worst=0,worstKey='',g0=Infinity,g1=-Infinity,any=false;
   for(const k of Object.keys(ref)){if(!k.startsWith(bore+':')||!k.endsWith('@'+t)||!d[k])continue;any=true;
    ref[k].forEach((p,i)=>{const dp=Math.abs(d[k][i]-p);if(dp>worst){worst=dp;worstKey=k.split('@')[0];}g0=Math.min(g0,p-101325);g1=Math.max(g1,p-101325);});}
   return any?`${worst.toFixed(1)} (${worstKey}) [${g0.toFixed(0)}..${g1.toFixed(0)}]`:'-';});
  console.log(`| ${label} vs ${refLabel} | ${bore} m | ${cells.join(' | ')} |`);}}

// Review 8.7 E1: allocation per unit of work from the HOLDUP_COST lines (transient cases only unless --static).
// Usage: node alloc-units.js [--static] label=file.xml ...
const fs=require('fs');let args=process.argv.slice(2);const st=args[0]==='--static';if(st)args.shift();
console.log('| run | alloc MB | per accepted step KB | per Newton solve KB | per Newton iteration KB (excl. Jacobian) | per Jacobian build KB | per reconstruction KB | per phase-check flash KB | per checkConservation KB | alloc MB per simulated s (transient) |');
console.log('|---|---|---|---|---|---|---|---|---|---|');
for(const a of args){const [label,file]=a.split('=');const t={};let cases=0;
 for(const l of fs.readFileSync(file,'utf8').split(/\r?\n/)){if(!l.startsWith('HOLDUP_COST '))continue;const isStatic=/case=static/.test(l);if(isStatic!==st)continue;cases++;
  for(const m of l.matchAll(/ ([A-Za-z]+)=([0-9.E+-]+)(?= |$)/g))t[m[1]]=(t[m[1]]||0)+Number(m[2]);const x=/threadAllocMB=([0-9.]+)/.exec(l);t.alloc=(t.alloc||0)+Number(x[1])*1048576;}
 const kb=x=>(x/1024).toFixed(1);const flashes=(t.flashNewton||0)+(t.flashReconstructed||0)+(t.flashFailed||0);
 console.log(`| ${label} | ${(t.alloc/1048576).toFixed(1)} | ${kb(t.alloc/t.accepted)} | ${kb(t.alloc/t.newtonSolves)} | ${kb((t.newtonAllocBytes-t.jacobianAllocBytes)/t.newtonIterations)} | ${kb(t.jacobianAllocBytes/t.jacobianBuilds)} | ${kb(t.reconstructAllocBytes/t.reconstructCalls)} | ${kb(t.phaseAllocBytes/Math.max(1,flashes))} | ${kb(t.conservationAllocBytes/t.accepted)} | ${st?'-':(t.alloc/1048576/(16*cases)).toFixed(2)} |`);}

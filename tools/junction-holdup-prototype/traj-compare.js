// Review 8.6: tank pressures at given times from HOLDUP_TRAJECTORY lines of several transient XMLs.
// Usage: node traj-compare.js <times comma list> <label=file.xml> ...
const fs=require('fs');const [, , times, ...runs]=process.argv;const want=times.split(',');
const data={};
for(const r of runs){const [label,file]=r.split('=');data[label]={};
 for(const l of fs.readFileSync(file,'utf8').split(/\r?\n/)){const m=/HOLDUP_TRAJECTORY case=(\S+) time=(\S+) P=\[([^\]]*)\]/.exec(l);if(!m||!want.includes(m[2]))continue;data[label][m[1]+'@'+m[2]]=m[3].split(', ').map(Number);}}
const labels=Object.keys(data);const ref=labels[0];
const keys=Object.keys(data[ref]);
console.log('| case @ t | '+labels.map(l=>l+' P1, P2 (Pa)').join(' | ')+' | '+labels.slice(1).map(l=>l+' - '+ref+' (Pa)').join(' | ')+' |');
console.log('|'+'---|'.repeat(1+2*labels.length-1));
for(const k of keys){const row=labels.map(l=>data[l][k]?data[l][k].map(x=>x.toFixed(2)).join(', '):'-');
 const diff=labels.slice(1).map(l=>data[l][k]?data[l][k].map((x,i)=>(x-data[ref][k][i]).toFixed(2)).join(', '):'-');
 console.log('| '+k+' | '+row.join(' | ')+' | '+diff.join(' | ')+' |');}

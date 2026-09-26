// Exact-once string replacement for CRLF sources: node ed.js <file> <pairs.json>; LF in the pairs becomes CRLF.
const fs=require('fs');const p=process.argv[2];let s=fs.readFileSync(p,'utf8');
const crlf=s.includes('\r\n');const fix=t=>crlf?t.replace(/\r?\n/g,'\r\n'):t;
const reps=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));
for(let [a,b] of reps){a=fix(a);b=fix(b);const n=s.split(a).length-1;if(n!==1){console.error('count '+n+' for: '+a.slice(0,90));process.exit(1);}s=s.replace(a,()=>b);}
fs.writeFileSync(p,s);console.log('ok '+reps.length);

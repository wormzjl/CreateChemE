// Review 8.7: thrown-attempt messages summed over the HOLDUP_COSTDETAIL lines of probe XMLs. Usage: node reject-reasons.js a.xml [b.xml ...]
const fs=require('fs');const t={};
for(const f of process.argv.slice(2))for(const l of fs.readFileSync(f,'utf8').split(/\r?\n/)){if(!l.startsWith('HOLDUP_COSTDETAIL'))continue;
 const body=l.replace(/^HOLDUP_COSTDETAIL case=\S+ \{/,'').replace(/\}$/,'');
 for(const m of body.matchAll(/((?:newton|other|approx)\|.*?)=(\d+)(?=, (?:newton|other|approx)\||$)/g))t[m[1]]=(t[m[1]]||0)+Number(m[2]);}
for(const [k,v] of Object.entries(t).sort((a,b)=>b[1]-a[1]))console.log(String(v).padStart(5),k);

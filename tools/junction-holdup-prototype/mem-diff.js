// Review 8.7 E2: retained bytes per island by class, two memory-probe XMLs side by side. Usage: node mem-diff.js a.xml b.xml
const fs=require('fs');const [, , fa, fb]=process.argv;
function read(f){const m={};for(const l of fs.readFileSync(f,'utf8').split(/\r?\n/)){const x=/HOLDUP_MEMORY_CLASS fixture=(\S+) class=(\S+) bytesPerIsland=(\d+) instancesPerIsland=(\S+)/.exec(l);if(x)(m[x[1]]||(m[x[1]]={}))[x[2]]=[+x[3],+x[4]];}return m;}
const a=read(fa),b=read(fb);
for(const fix of Object.keys(a)){console.log('| '+fix+' class | bytes/island A | bytes/island B | instances/island A | instances/island B |');console.log('|---|---|---|---|---|');
 const keys=[...new Set([...Object.keys(a[fix]),...Object.keys(b[fix]||{})])].sort((x,y)=>((a[fix][y]||[0])[0])-((a[fix][x]||[0])[0]));
 for(const k of keys){const p=a[fix][k]||[0,0],q=(b[fix]||{})[k]||[0,0];console.log(`| ${k} | ${p[0]} | ${q[0]} | ${p[1]} | ${q[1]} |`);}}

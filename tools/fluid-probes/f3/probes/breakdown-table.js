// Builds the before/after payload table of f3-tables.md from the two probe outputs.
const fs=require("fs");
function parse(file){const groups={};let g=null;for(const line of fs.readFileSync(file,"utf8").split("\n")){const h=line.match(/^## (.*?) \(/);if(h){g=h[1];groups[g]={};continue;}
  const m=line.match(/^\| (.*?) \| ([0-9.]+) \|/);if(g&&m)groups[g][m[1]]=parseFloat(m[2]);}return groups;}
const before=parse("documentation/fluid-followups/f3-logs/f3-payload-before.txt"),after=parse("documentation/fluid-followups/f3-logs/f3-payload-after.txt");
const sum=(o,keys)=>keys.reduce((a,k)=>a+(o[k]||0),0);
const B=(o,p)=>Object.keys(o).filter(k=>p.test(k));
const rows=[
 ["island identity (dimension, package, compressibility; unit header)",o=>sum(o,B(o,/^identity/)),o=>sum(o,["header (identity, revision, generation)","dimension and package"])],
 ["thermodynamic revision and energy reference",o=>sum(o,["propertyRevision","energy reference"]),o=>0],
 ["graph topology (node ids, elevations, kinds, volumes; pipe ends, sections, controls)",o=>sum(o,B(o,/^graph\.(nodes identity|pipes identity)/)),o=>sum(o,["graph topology (nodes and pipes, once)"])],
 ["graph state (inventories, phases, pipe masks and filters)",o=>sum(o,B(o,/^graph\.(nodes inventory|nodes phase|pipes state)/)),o=>sum(o,["graph state (inventories, phases, pipe masks and filters)"])],
 ["approximation anchor (revision, graph, modes)",o=>sum(o,B(o,/^anchor/)),o=>sum(o,["anchor"])],
 ["last interval: scalars, flows, heads, modes, boundaries, reasons",o=>sum(o,B(o,/^history\.(scalars|flows|heads|modes|boundaries|rejectionReasons)|^history \(null\)/)),o=>sum(o,B(o,/^history: (scalars|boundaries|reasons)/))],
 ["last interval: pipe transfers",o=>sum(o,["history.pipes (pipe transfers)"]),o=>sum(o,["history: pipe transfers"])],
 ["status, allowance, fences",o=>sum(o,["status","allowance","fences"]),o=>sum(o,["status","allowance","fences"])],
 ["certificate: interval start graph",o=>sum(o,B(o,/^certifiedFrom/)),o=>sum(o,["certificate: interval start graph"])],
 ["certificate: signature",o=>0,o=>sum(o,["certificate signature"])],
];
let out="";
for(const [gb,ga] of [["rest100 CLOSED","rest100 CLOSED"],["stress100 THROUGH","stress100 THROUGH"],["in-game rest line","in-game rest line"],["lone tank","lone tank"]]){
  const b=before[gb],a=after[ga];out+=`\n#### ${gb} (mean per island)\n\n| section | format 3 JSON, bytes | format 4 binary, bytes |\n|---|---|---|\n`;
  let tb=0,ta=0;for(const [name,fb,fa] of rows){const x=Math.round(fb(b)),y=Math.round(fa(a));tb+=x;ta+=y;out+=`| ${name} | ${x.toLocaleString("en")} | ${y.toLocaleString("en")} |\n`;}
  out+=`| **payload / unit** | **${Math.round(b["payload (JSON as written)"]).toLocaleString("en")}** | **${Math.round(a["unit"]).toLocaleString("en")}** |\n`;
  out+=`| gzip of the payload / unit | ${Math.round(b["gzip of the payload"]).toLocaleString("en")} | ${Math.round(a["gzip of the unit"]).toLocaleString("en")} |\n`;
  out+=`| per-island envelope: NBT island compound / index row | ${Math.round(b["NBT island envelope (scalars, certificate strings, digest)"]).toLocaleString("en")} | 133 |\n`;
  out+=`| check: sum of the rows | ${tb.toLocaleString("en")} | ${ta.toLocaleString("en")} |\n`;
}
fs.writeFileSync("documentation/fluid-followups/f3-logs/payload-table.md",out);console.log(out);

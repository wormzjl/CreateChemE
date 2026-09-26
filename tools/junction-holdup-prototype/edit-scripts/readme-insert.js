// Inserts the review 8.7 README text (edit-scripts/readme-*.md) once. Usage: node readme-insert.js (from this folder's parent).
const fs=require('fs');let s=fs.readFileSync('README.md','utf8');
const contents=fs.readFileSync('edit-scripts/readme-contents-8.7.md','utf8'),runs=fs.readFileSync('edit-scripts/readme-runs-8.7.md','utf8');
if(s.includes('holdup-prototype-be-fast.patch'))throw new Error('already inserted');
const at=s.indexOf('- Probes: `-PsolverDiag=true`');if(at<0)throw new Error('no probes bullet');
s=s.slice(0,at)+contents+s.slice(at);
const row='| 90 | final code, defaults, no init script, the fluid suites (`run90-gates/`) | - | **399/399** in 91 classes, 0 skipped |\n';
const r=s.indexOf(row);if(r<0)throw new Error('no run 90 row');
s=s.slice(0,r+row.length)+runs+s.slice(r+row.length);
const h='runs 84-90 (backward Euler and cost) in §8.6).';if(!s.includes(h))throw new Error('no header');
s=s.replace(h,'runs 84-90 (backward Euler and cost) in §8.6, runs 91-99 (cost per solve, cadence and memory) in §8.7).');
const p='nine for the be patch).';if(!s.includes(p))throw new Error('no patch count');
s=s.replace(p,"nine for the be patch, thirteen for the be-fast patch). Runs 91+ record their command line as the first line of each log (`COMMAND:`); their base set is run 87b's, `BASE FINAL '-PbeStateCap=0.05' -PsolverDiag=true`, and the gate runs 98-99 drop `-PsolverDiag`.");
fs.writeFileSync('README.md',s);console.log('inserted');

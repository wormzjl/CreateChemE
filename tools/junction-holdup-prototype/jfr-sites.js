// Review 8.7 E1: allocation (jdk.ObjectAllocationSample weight) and CPU (jdk.ExecutionSample) attribution from a JFR JSON dump.
// Usage: jfr print --json --stack-depth 96 --events jdk.ObjectAllocationSample,jdk.ExecutionSample <file.jfr> > x.json; node jfr-sites.js x.json [top]
// Prints: top leaf sites (class.method:line) by bytes; bytes by the solver-level caller (the first frame in the
// network/solver packages, with its line), and by the phase (flash / decode / Jacobian / reconstruct / ... ) the stack runs in.
const fs=require('fs');const [, , file, topArg]=process.argv;const top=Number(topArg||25);
const events=JSON.parse(fs.readFileSync(file,'utf8')).recording.events;
const fr=f=>f.method.type.name.split('/').join('.').replace('com.wormzjl.createcheme.science.','')+'.'+f.method.name+':'+f.lineNumber;
function phase(frames){const names=frames.map(f=>f.method.type.name+'.'+f.method.name);
 const has=s=>names.some(n=>n.includes(s));
 if(has('balancedBoundarySeed')||has('seedBoundaryJunctions'))return 'cold seed (balancedBoundarySeed)';
 if(has('coldRateSeed')||has('portRate'))return 'cold rate solve';
 if(has('InventoryEquilibrium.refresh'))return 'interval refresh (InventoryEquilibrium)';
 if(has('PassiveStepSolver.phaseCorrection'))return 'phase check (phaseCorrection flash)';
 if(has('PassiveStepSolver.initialPhaseSeeds'))return 'trace seed (initialPhaseSeeds flash)';
 if(has('differentiateEntries')||has('SparseNewton.differentiate'))return 'Jacobian build';
 if(has('SparseNewton.solve'))return 'Newton (residuals, line search, LU)';
 if(has('ConservativeTransport.reconstruct'))return 'reconstruction';
 if(has('checkConservation'))return 'checkConservation';
 if(has('Equations.<init>')||has('PhaseLayout.<init>'))return 'equations/layout build';
 if(has('PassiveStepSolver$Equations.residual'))return 'gate residual';
 if(has('PipeTransfer'))return 'PipeTransfer sample/accumulate';
 if(has('PassiveStepSolver.solve'))return 'step solve other';
 if(has('PassiveIntervalSolver'))return 'interval solver other';
 return 'outside solver';}
const leaf={},caller={},ph={},cpu={},cpuPh={};let total=0,samples=0;
for(const e of events){const frames=(e.values.stackTrace&&e.values.stackTrace.frames)||[];
 if(e.type==='jdk.ObjectAllocationSample'){const w=e.values.weight;total+=w;
  const l=frames.length?fr(frames[0])+' ['+(e.values.objectClass?e.values.objectClass.name:'?')+']':'?';leaf[l]=(leaf[l]||0)+w;
  const c=frames.find(f=>/fluid[/.](network|solver)[/.]/.test(f.method.type.name)&&!/Lambda/.test(f.method.type.name));const ck=c?fr(c):"?";caller[ck]=(caller[ck]||0)+w;
  const p=phase(frames);ph[p]=(ph[p]||0)+w;}
 else if(e.type==='jdk.ExecutionSample'){samples++;const l=frames.length?fr(frames[0]):'?';cpu[l]=(cpu[l]||0)+1;const p=phase(frames);cpuPh[p]=(cpuPh[p]||0)+1;}}
const show=(t,o,n,unit)=>{console.log('\n'+t);for(const [k,v] of Object.entries(o).sort((a,b)=>b[1]-a[1]).slice(0,n))console.log((unit==='B'?(v/1048576).toFixed(1).padStart(8)+' MB '+(100*v/total).toFixed(1).padStart(5)+'%':String(v).padStart(6)+' '+(100*v/samples).toFixed(1).padStart(5)+'%')+'  '+k);};
console.log('allocation samples weight total '+(total/1048576).toFixed(1)+' MB; execution samples '+samples);
show('bytes by phase',ph,20,'B');show('bytes by solver-level caller (first network/solver frame)',caller,top,'B');show('bytes by leaf site [class]',leaf,top,'B');
show('CPU samples by phase',cpuPh,20,'n');show('CPU samples by leaf',cpu,top,'n');
// The outermost frames of the samples no phase rule matched, for checking what "outside solver" is.
const outside={};for(const e of events){if(e.type!=='jdk.ObjectAllocationSample')continue;const frames=(e.values.stackTrace&&e.values.stackTrace.frames)||[];if(phase(frames)!=='outside solver')continue;
 const k=frames.slice(0,40).filter(f=>/wormzjl/.test(f.method.type.name)).slice(0,3).map(fr).join(' < ')||'(no project frame)';outside[k]=(outside[k]||0)+e.values.weight;}
show('outside-solver bytes by first project frames',outside,12,'B');

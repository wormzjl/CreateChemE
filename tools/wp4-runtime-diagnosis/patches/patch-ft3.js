const fs=require('fs');
const p='patch-src/com/wormzjl/createcheme/science/fluid/thermo/FluidThermodynamics.java';
let s=fs.readFileSync(p,'utf8');
function rep(a,b){if(!s.includes(a))throw new Error('missing: '+a.slice(0,80));s=s.replace(a,b);}
rep(`            if(required<=w){DIAG[2]++;diagBranch=-1;return state(t,p,split[0],split[1],w-required,required,pc,terms);}`,
`            if(required<=w){DIAG[2]++;diagBranch=-1;return state(t,p,split[0],split[1],w-required,required,pc,terms);}
            // [diag.flashFix] Unsaturated gas: every mole of water is vapour and pc solves pc + w R T / vg(pc) = p. Its root sits
            // w R T / vg below p (a trace of 1e-13 puts it 1e-13 p below), so start there, from the split already at hand, and
            // verify with the bisection's own residual test; the bisection below remains the fallback.
            if(DIAG_FLASH_FIX&&vg>0) {
                double guess=p/(1+w*R*t/(vg*pc));
                for(int fixedPoint=0;fixedPoint<3;fixedPoint++) {
                    checkpoint.run();
                    var trial=splitHydrocarbon(t,p,guess,hc,checkpoint,terms);
                    double tv=sum(trial[1]);double tvg=tv>0?tv*hydrocarbon.phase(t,guess,trial[1],PhaseRoot.VAPOR,terms).molarVolume():0;
                    if(!(tvg>0))break;
                    double residual=guess+w*R*t/tvg-p;
                    if(Math.abs(residual)<1e-8*p) {
                        var fixed=state(t,p,trial[0],trial[1],0,w,guess,terms);
                        if(Math.abs(guess+fixed.waterPartialPressure-p)<=1e-6*p){DIAG[10]++;diagBranch=-1;return fixed;}
                        break;
                    }
                    guess=p-w*R*t/tvg;
                }
                DIAG[11]++;
            }`);
rep(`    static final int DIAG_MODE=`,`    static final boolean DIAG_FLASH_FIX=Boolean.getBoolean("diag.flashFix");
    static final int DIAG_MODE=`);
fs.writeFileSync(p,s);console.log('patched3');

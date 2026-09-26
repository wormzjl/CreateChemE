const fs=require('fs');
const p='patch-src/com/wormzjl/createcheme/science/fluid/thermo/FluidThermodynamics.java';
let s=fs.readFileSync(p,'utf8');
function rep(a,b){if(!s.includes(a))throw new Error('missing: '+a.slice(0,80));s=s.replace(a,b);}
// counters
rep(`    static final int DIAG_MODE=`,`    /** [0..3] flashes by branch (no hydrocarbon, dry split, saturated split, bisection); [4..7] split iterations by branch;
     * [8] bisection iterations; [9] splits in bisection. */
    public static final long[] DIAG=new long[16];
    public static final java.util.TreeMap<Integer,long[]> DIAG_WFRAC=new java.util.TreeMap<>();
    private static int diagBranch=-1;
    static final int DIAG_MODE=`);
rep(`        if(nh==0)return state(t,p,hc,hc,p>=saturationPressure(t)?w:0,p>=saturationPressure(t)?0:w,0);
        if(w==0) {var terms`,`        if(nh==0){DIAG[0]++;return state(t,p,hc,hc,p>=saturationPressure(t)?w:0,p>=saturationPressure(t)?0:w,0);}
        diagBranch=w==0?1:2;
        if(w==0) {DIAG[1]++;var terms`);
rep(`            if(required<=w)return state(t,p,split[0],split[1],w-required,required,pc,terms);`,`            if(required<=w){DIAG[2]++;diagBranch=-1;return state(t,p,split[0],split[1],w-required,required,pc,terms);}`);
rep(`        double low=1e-6,high=p;double[][] split=null;double pc=high;`,`        DIAG[3]++;diagBranch=3;DIAG_WFRAC.computeIfAbsent((int)Math.floor(Math.log10(w/(nh+w))),key->new long[1])[0]++;
        double low=1e-6,high=p;double[][] split=null;double pc=high;`);
rep(`            checkpoint.run();pc=(low+high)*.5;`,`            checkpoint.run();DIAG[8]++;DIAG[9]++;pc=(low+high)*.5;`);
rep(`        var result=state(t,p,split[0],split[1],0,w,pc,terms);`,`        diagBranch=-1;var result=state(t,p,split[0],split[1],0,w,pc,terms);`);
rep(`            checkpoint.run();double f0=0,f1=0;`,`            checkpoint.run();if(diagBranch>0)DIAG[4+diagBranch]++;double f0=0,f1=0;`);
fs.writeFileSync(p,s);console.log('patched2');

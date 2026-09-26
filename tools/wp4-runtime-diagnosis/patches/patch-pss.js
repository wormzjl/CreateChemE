const fs=require('fs');
const p='patch-src/com/wormzjl/createcheme/science/fluid/network/PassiveStepSolver.java';
let s=fs.readFileSync(p,'utf8');
function rep(a,b){if(!s.includes(a))throw new Error('missing: '+a.slice(0,90));s=s.replace(a,b);}
rep(`    private static final double FILTER_CAPACITY_MARGIN=1e-12;`,`    private static final double FILTER_CAPACITY_MARGIN=1e-12;
    // ---- WP4 runtime diagnosis only (scratch copy under build/diagnosis, never tracked) ----
    static final boolean DIAG_TRACE=Boolean.getBoolean("diag.pumpTrace");
    /** suction: a pump edge's static column is always its suction's density (fix E2). */
    static final boolean DIAG_SUCTION_COLUMN="suction".equals(System.getProperty("diag.pumpColumn"));
    /** column: the pump's mode test reads the edge's own frozen column (fix E1). */
    static final boolean DIAG_DEMAND_COLUMN="column".equals(System.getProperty("diag.pumpDemand"));
    public static final long[] DIAG=new long[8];`);
rep(`            if(margin>shutoffBand(Math.max(a.state().pressure(),b.state().pressure()),1e-9))modes.set(i,FlowControl.Mode.PUMP_HEAD_LIMIT);`,
`            if(DIAG_TRACE)System.out.printf("    [solve start] carried CLOSED: reopen margin %.3f Pa -> %s (Pa=%.2f Pb=%.2f rho_a=%.4f)%n",margin,margin>shutoffBand(Math.max(a.state().pressure(),b.state().pressure()),1e-9)?"PUMP_HEAD_LIMIT":"stays CLOSED",a.state().pressure(),b.state().pressure(),a.state().mass()/a.state().volume());
            if(margin>shutoffBand(Math.max(a.state().pressure(),b.state().pressure()),1e-9)){modes.set(i,FlowControl.Mode.PUMP_HEAD_LIMIT);DIAG[0]++;}`);
rep(`                    double limit=riseLimit(pump,rho),margin=limit-demand(graph,pipe,states,rho);`,
`                    double limit=riseLimit(pump,rho),margin=limit-demand(graph,pipe,states,DIAG_DEMAND_COLUMN?equations.headDensities[i]:rho);`);
rep(`                    work+=dt*Math.max(0,flows[i])/rho*Math.max(0,heads[i])/pump.efficiency();`,
`                    work+=dt*Math.max(0,flows[i])/rho*Math.max(0,heads[i])/pump.efficiency();
                    if(DIAG_TRACE&&next!=mode&&!changed)System.out.printf("    [pass %d dt=%.3e] %s -> %s: flow=%.5e head=%.2f limit=%.2f margin=%.3f band=%.3g rho_suction=%.4f column=%.4f Pa=%.2f Pb=%.2f%n",
                            pass,dt,mode,next,flows[i],heads[i],limit,margin,band,rho,equations.headDensities[i],up.pressure(),states.get(pipe.second()).pressure());
                    if(next==FlowControl.Mode.CLOSED&&mode==FlowControl.Mode.PUMP_HEAD_LIMIT&&!changed)DIAG[1]++;`);
rep(`                headDensities[edge]=fromFirst?first:second;`,`                headDensities[edge]=fromFirst?first:second;
                if(DIAG_SUCTION_COLUMN&&pipe.control() instanceof FlowControl.Pump)headDensities[edge]=first;`);
fs.writeFileSync(p,s);console.log('patched pss');

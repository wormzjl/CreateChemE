package com.wormzjl.createcheme.runtime;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;

/** WP4 runtime diagnosis (scratch, not tracked): where the six-tank pumped fill's first tick spends its checkpoints. */
public class DiagPumpedFillProbe {
    static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    static final FluidThermodynamics model=FluidTestSupport.networkModel();
    static double[] pure(int component){double[] n=new double[MaterialTestBasis.NETWORK+1];n[component]=1;return n;}
    static PassiveNetwork line(String layout) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(int i=0;i<layout.length();i++) {
            var kind=switch(layout.charAt(i)){case 'G'->TopologyCompiler.Kind.GENERATOR;case 'U'->TopologyCompiler.Kind.PUMP;case 'P'->TopologyCompiler.Kind.PIPE;
                case 'R'->TopologyCompiler.Kind.RESERVOIR;case 'V'->TopologyCompiler.Kind.VOID;default->throw new IllegalArgumentException(layout);};
            var device=new PhysicalFluidTopology.Device(i+1,new PhysicalFluidTopology.Position("minecraft:overworld",i,64,0),kind,PhysicalFluidTopology.Direction.EAST,BLOCK,
                    kind==TopologyCompiler.Kind.PUMP?new FlowControl.Pump(.01,500000,1):new FlowControl.Passive());
            devices.add(device);
            if(kind==TopologyCompiler.Kind.GENERATOR)boundaries.put(device.id(),new FluidDeviceSpec(1,298.15,101325,pure(MaterialTestBasis.NETWORK)).initialize(device,model,()->{}));
            else if(kind==TopologyCompiler.Kind.RESERVOIR||kind==TopologyCompiler.Kind.VOID)boundaries.put(device.id(),new FluidDeviceSpec(1,298.15,101325,pure(MaterialTestBasis.NITROGEN)).initialize(device,model,()->{}));
        }
        var compiled=PhysicalFluidTopology.compile(devices,boundaries);
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        return connected.getFirst().graph();
    }
    static String shortName(String cls){int dot=cls.lastIndexOf('.');return dot<0?cls:cls.substring(dot+1);}
    static final Set<String> OWNERS=Set.of("PassiveStepSolver","SparseNewton","TrBdf2StepSolver","PassiveIntervalSolver","SolidEventIntegrator","ConservativeTransport","InventoryEquilibrium","FluidThermodynamics");
    public static void main(String[] args) {
        String mode=args.length>0?args[0]:"count";String layout=args.length>1?args[1]:"GUPRPRPRPRPRPR";double duration=args.length>2?Double.parseDouble(args[2]):.05;
        var graph=line(layout);
        if(mode.equals("warm")) {
            // Warm-JIT wall time of the same cold-solver slice: 12 repetitions in one JVM, the last 6 averaged.
            long[] walls=new long[12];long[] cps=new long[1];
            for(int r=0;r<walls.length;r++){cps[0]=0;long t0=System.nanoTime();
                try{new PassiveIntervalSolver(model).solve(graph,duration,PassiveIntervalSolver.Settings.defaults(),()->cps[0]++,RetainedSolver.COLD_START_SECONDS);}catch(RuntimeException failed){System.out.println("FAILED "+failed);}
                walls[r]=System.nanoTime()-t0;}
            double mean=0;for(int r=6;r<12;r++)mean+=walls[r]/6e6;
            System.out.printf("WARM checkpoints=%d wall ms per slice (last 6 of 12) mean=%.1f runs=%s -> %.3f us per checkpoint%n",cps[0],mean,Arrays.toString(Arrays.stream(walls).map(w->w/1_000_000).toArray()),mean*1000/cps[0]);
            return;
        }
        System.out.println("graph: nodes="+graph.reservoirs().size()+" pipes="+graph.pipes().size()+" water="+System.getProperty("diag.water","region1")+" kappa="+System.getProperty("diag.waterKappa","-")+" pumpRef="+model.pumpReferenceDensity());
        for(int i=0;i<graph.reservoirs().size();i++){var n=graph.reservoirs().get(i);System.out.printf("  node[%d] id=%d %s junction=%s fixed=%s P=%.2f V=%.4g rho=%.4f vel=%.4g%n",i,n.id(),n.kind(),n.junction(),n.fixed(),n.state().pressure(),n.state().volume(),n.state().mass()/n.state().volume(),model.velocityLimit(n.state()));}
        for(var p:graph.pipes())System.out.println("  pipe id="+p.id()+" "+p.first()+"->"+p.second()+" "+p.control().getClass().getSimpleName()+" area="+p.minimumArea());
        SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
        long[] count={0};
        var sites=new HashMap<String,long[]>();var chains=new HashMap<String,long[]>();
        long[] attemptStart={0};var attemptCosts=new ArrayList<Long>();
        StackWalker walker=StackWalker.getInstance();
        Runnable checkpoint=mode.equals("attr")?()->{
            count[0]++;
            var frames=walker.walk(s->s.skip(1).limit(60).toList());
            var site=frames.get(0);String siteKey=shortName(site.getClassName())+"."+site.getMethodName()+":"+site.getLineNumber();
            if(siteKey.startsWith("PassiveIntervalSolver.run:")){attemptCosts.add(count[0]-attemptStart[0]);attemptStart[0]=count[0];}
            sites.computeIfAbsent(siteKey,k->new long[1])[0]++;
            var chain=new StringBuilder(siteKey);int owners=0;
            for(int i=1;i<frames.size()&&owners<4;i++){var f=frames.get(i);String c=shortName(f.getClassName());int dollar=c.indexOf('$');String base=dollar<0?c:c.substring(0,dollar);
                if(OWNERS.contains(base)){chain.append(" <- ").append(c).append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());owners++;}}
            chains.computeIfAbsent(chain.toString(),k->new long[1])[0]++;
        }:()->count[0]++;
        long start=System.nanoTime();
        try {
            var result=new PassiveIntervalSolver(model).solve(graph,duration,PassiveIntervalSolver.Settings.defaults(),checkpoint,RetainedSolver.COLD_START_SECONDS);
            System.out.println("RESULT accepted="+result.acceptedSubsteps()+" rejected="+result.rejectedSubsteps()+" reasons="+result.rejectionReasons()+" modes="+result.endpointModes());
            for(int i=0;i<result.graph().reservoirs().size();i++){var n=result.graph().reservoirs().get(i);var s=n.state();System.out.printf("  end node[%d] %s P=%.1f T=%.3f hcL=%.3e water=%.3e (%.4f) vapour=%.4f steamPp=%.1f rho=%.3f kw=%.3e vel=%.4g%n",i,n.kind(),s.pressure(),s.temperature(),s.liquidVolume(),s.waterVolume(),s.waterVolume()/s.volume(),s.vaporVolume(),s.waterPartialPressure(),s.mass()/s.volume(),s.waterCompressibility(),model.velocityLimit(s));}
            System.out.println("  flows="+Arrays.toString(result.averageMassFlows()));
        }catch(RuntimeException failed){System.out.println("FAILED: "+failed);}
        long wall=System.nanoTime()-start;
        attemptCosts.add(count[0]-attemptStart[0]);
        System.out.println("CHECKPOINTS "+count[0]+" wallMs="+wall/1e6);
        var sample=SolverDiagnostics.sample();
        for(String name:SolverDiagnostics.names()){long v=sample.value(name);if(v!=0&&!name.endsWith("Nanos"))System.out.println("  counter "+name+"="+v);}
        var d=FluidThermodynamics.DIAG;
        System.out.printf("FLASH branches: noHC=%d dry=%d saturated=%d bisection=%d | split iterations: dry=%d satTry=%d bisection=%d | bisection steps=%d | fixedPoint accepted=%d fallback=%d%n",d[0],d[1],d[2],d[3],d[5],d[6],d[7],d[8],d[10],d[11]);
        System.out.println("FLASH bisection by log10(water mole fraction): "+FluidThermodynamics.DIAG_WFRAC.entrySet().stream().map(e->e.getKey()+":"+e.getValue()[0]).toList());
        var attempts=sample.attempts();
        System.out.println("ACCURACY ATTEMPTS "+attempts.size());
        if(mode.equals("attr")||mode.equals("attempts"))for(var at:attempts)System.out.printf("  #%d step=%.3e accepted=%s %s err=%.3e%n",at.index(),at.step(),at.accepted(),at.dominant(),at.error());
        if(mode.equals("attr")) {
            System.out.println("SITES");
            sites.entrySet().stream().sorted((x,y)->Long.compare(y.getValue()[0],x.getValue()[0])).forEach(e->System.out.printf("  %9d %5.1f%% %s%n",e.getValue()[0],100.*e.getValue()[0]/count[0],e.getKey()));
            System.out.println("CHAINS");
            chains.entrySet().stream().sorted((x,y)->Long.compare(y.getValue()[0],x.getValue()[0])).limit(50).forEach(e->System.out.printf("  %9d %5.1f%% %s%n",e.getValue()[0],100.*e.getValue()[0]/count[0],e.getKey()));
            System.out.println("ATTEMPT COSTS (checkpoints per interval attempt, in order; first entry = before the first attempt)");
            System.out.println("  "+attemptCosts);
        }
    }
}

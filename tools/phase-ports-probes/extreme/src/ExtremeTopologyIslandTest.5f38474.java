package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner's two "extreme" gas islands, placed as blocks and compiled by {@link PhysicalFluidTopology#compile}, then
 * integrated the way the island runtime runs a placed island: every interval a job ({@link PassiveIntervalSolver#replayStart}
 * from the committed interval, then the settings' initial step), as {@link MixedGasJunctionTransientTest} does. Nitrogen at
 * 350 K everywhere; the world's default blocks (1 m3 tanks and generators, 1 m of 50 mm pipe per block;
 * {@code FluidWorldAuthority.place}). See documentation/2026-09-26-phase-ports-and-compressor/EXTREME_TOPOLOGY_TESTS.md.
 *
 * <h2>The geometries (x east, z south, all at y = 64; pressures in kPa)</h2>
 *
 * <p><b>Case 1</b>, "10 generator in one line with 10 pipe block, connected to another 10 pipe blocks and 10 tanks, each
 * with different pressure": a 10 x 4 block grid, a row of generators, two rows of pipe blocks, a row of tanks.
 * <pre>
 *   x:     0    1    2    3    4    5    6    7    8    9
 *   z=0  G110 G140 G170 G200 G130 G160 G190 G120 G150 G180
 *   z=1    P    P    P    P    P    P    P    P    P    P
 *   z=2    P    P    P    P    P    P    P    P    P    P
 *   z=3  T140 T110 T180 T150 T120 T190 T160 T130 T100 T170
 * </pre>
 * A pipe block connects on all six faces ({@code Device.connects}: not an actuator, not a filter), so the two pipe rows
 * are a 2 x 10 mesh of junctions joining every column; two boundaries side by side never connect
 * ({@code compile} skips a boundary-boundary pair). The grid is the only reading the words admit: a straight line of 40
 * blocks would leave nine generators and nine tanks touching nothing but each other.
 *
 * <p><b>Case 2</b>, "10 generator with 10 tanks arranged in between them, with a line of pipes connecting all on the
 * sides": one row alternating generator, tank, generator, ... with a parallel line of 20 pipe blocks beside it. "On the
 * sides" also reads as a pipe line on both sides of the row, so that is the second fixture, {@code ALTERNATING_BOTH_SIDES}
 * (the same row with a second line at z = -1).
 * <pre>
 *   x:     0    1    2    3    4    5    6    7    8    9   10   11   12   13   14   15   16   17   18   19
 *   z=-1   P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P   (both-sides reading only)
 *   z=0  G110 T140 G140 T110 G170 T180 G200 T150 G130 T120 G160 T190 G190 T160 G120 T130 G150 T100 G180 T170
 *   z=1    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P    P
 * </pre>
 * The generators run from 110 to 200 kPa in one order and the tanks from 100 to 190 kPa in another, so several tanks start
 * above the generator beside them (grid columns 0 and 5, row positions 1, 5 and 11).
 *
 * <h2>What the compiler makes of them</h2>
 * A pipe block with exactly two connections collapses into the run through it; every other block is a node, and every
 * maximal run between nodes is one compiled connection. Grid of n columns (n >= 2): every pipe block has three or four
 * connections, so 4n nodes (n generators, n tanks, 2n junctions) and 5n - 2 connections (n generator, n tank, n vertical,
 * 2(n - 1) horizontal): 40 nodes, 20 junctions, 48 connections. Alternating row of k pairs (2k devices): the two end pipe
 * blocks have two connections and collapse, so 2k - 2 junctions and 4k - 3 connections (2k - 2 device stubs, two end runs
 * through the collapsed blocks, 2k - 3 between junctions): 38 nodes, 18 junctions, 37 connections; both sides doubles the
 * pipe part: 56 nodes, 36 junctions, 74 connections. One island each.
 *
 * <h2>The physics the oracles rest on</h2>
 * A generator only supplies ({@code PassiveStepSolver.boundaryAllowed}); a tank is a closed adiabatic volume whose pressure
 * rises exactly when it gains fluid and falls when it loses it; a junction's mass is pinned, so its net-mass row is
 * algebraic, and every connection's flow is a nondecreasing function of its driving pressure (the velocity cap included).
 * So at every accepted step no junction lies outside the pressures of the boundaries that carry flow into or out of it,
 * and a chain of falling pressures from any node must end at a boundary that receives, which is a tank. Hence:
 * <ul>
 * <li>no tank ever rises above the highest generator {@code P_max} (every tank starts below it);</li>
 * <li>the lowest tank never falls, since its junction cannot lie below it: <b>the lowest tank pressure is nondecreasing</b>.
 * This is the monotonicity the physics defends. A single tank is not monotone in general: a tank at the top of the band
 * discharges into the mesh until the mesh rises past it (measured: 7 or 8 of 10 tanks monotone at 0.1 s);</li>
 * <li>at rest every open connection carries nothing, so every tank ends at {@code P_max} and every other generator is
 * closed;</li>
 * <li>a boundary above its junction at both ends of an interval supplies (a tank discharges); one below it at both ends
 * receives (a generator is closed).</li>
 * </ul>
 *
 * <h2>Formerly an open defect: the full cases held on their first interval (fixed by decision D12)</h2>
 * With the default 100 m/s velocity cap all three full fixtures failed their first interval at 5 s and at 0.1 s: pass 0 of
 * the first step never converged ("Newton iteration limit" or "line search stalled", "active-set pass=0", Newton iterates
 * outside the nitrogen domain above 2 MPa and below 100 Pa, a singular Jacobian on the alternating rows), and halving the
 * step did not help because the junction rows are algebraic. Mechanism, as far as the solver shows it: on a first solve
 * the start-of-solve closures do not reach a generator edge ({@code closeDeadHeads} skips runs ending at a junction, and
 * {@code closeIllegalStarts} waits for an accepted solve on the structure), so pass 0 solved every generator as a two-way
 * fixed-pressure boundary, a through-flow network between generators 10 to 90 kPa apart. At 100 m/s a half block of 50 mm
 * nitrogen saturates at a few kilopascals of driving pressure ({@link #capDrop}), so most of that network is on the cap's
 * branch, whose row does not depend on the end pressures; a junction whose connections are all capped has a (near) empty
 * pressure column, the Newton step in its pressure leaves the domain, and the pass never reaches the point at which the
 * active set would close the low generators. Evidence: equal generator pressures pass whatever the tanks hold; the same
 * fixtures at the configurable maximum velocity (100000 m/s, where the acoustic bound caps instead) integrate every
 * interval ({@link #theFullCasesIntegrateWhenTheVelocityCapDoesNotSaturate}); the pressures scaled to a fifth of their
 * spread integrate at the default cap ({@link #theCasesAtAFifthOfTheSpreadIntegrate}). The boundary was irregular, as a
 * Newton basin is: the grid passed at 2 columns and failed at 3, the alternating rows passed at 3 pairs and failed at 4, and
 * at spread 0.22 to 0.26 the alternating rows failed while 0.28 passed. The cold rate seed failed the same way (checked:
 * "active-set pass=0" on the port graph), so the steps started from the compiler's junction guess.
 *
 * <p>Owner decision D12 (a generator only pushes when its pressure allows and never receives, from the cold start's first
 * pass): the cold start's rate seed now closes before its pass 0 the generator runs that a one-way pressure estimate of the
 * island shows receiving, and starts its junctions from that estimate ({@code PassiveStepSolver.closeColdReceivingGenerators}),
 * so the seed converges and the first step's {@code closeIllegalStarts} starts it with one-way generators. With
 * {@link #FULL_CASES_OPEN_DEFECT} now false, {@link #theFullCasesHoldOnTheirFirstIntervalOpenDefect} is the regression: the
 * full fixtures at the default cap run every oracle of the passing cases.
 */
class ExtremeTopologyIslandTest {
    /** True while the full fixtures reproduced the first-interval hold described in the class comment; false since
     * decision D12 (the flagged test then runs every oracle on them). Setting it true on a tree without D12 reproduces the
     * recorded failure. */
    static final boolean FULL_CASES_OPEN_DEFECT=false;
    private static final String DIMENSION="minecraft:overworld";
    private static final int Y=64;
    /** The world's default block; see FluidWorldAuthority.place. */
    private static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    private static final double TEMPERATURE=350;
    static final double[] GENERATORS={110e3,140e3,170e3,200e3,130e3,160e3,190e3,120e3,150e3,180e3};
    static final double[] TANKS={140e3,110e3,180e3,150e3,120e3,190e3,160e3,130e3,100e3,170e3};
    /** A reduced case scales every pressure's distance from these centres by its spread. */
    static final double GENERATOR_CENTRE=155e3,TANK_CENTRE=145e3,REDUCED_SPREAD=.2;
    /** The ledger bounds (relative to max(1, initial)); the 5 s against 0.1 s bound of CadenceTrajectoryQualificationTest
     * (MixedGasJunctionTransientTest compares no two cadences), with its 0.5 K on temperature. */
    static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-10,CADENCE_TOLERANCE=.02,CADENCE_TEMPERATURE=.5;
    /** The solver's own zero-flow threshold (|q| <= 1e-10 kg/s, the pass loop's illegal-direction test), and the pressure
     * margin a direction is judged beyond. */
    static final double ZERO_FLOW=1e-10,DIRECTION_MARGIN=100;
    /** Where every tank must end relative to P_max after the run (measured at most 1.4e-4 Pa, 7e-10). */
    static final double REST=1e-6;
    /** The configuration's largest maximumVelocityMetresPerSecond (CreateChemE's defineInRange). */
    static final double LARGEST_CONFIGURED_VELOCITY=100000;
    private final FluidThermodynamics defaultModel=FluidTestSupport.networkModel();
    private final FluidThermodynamics uncappedModel=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9,LARGEST_CONFIGURED_VELOCITY);

    enum Geometry{GRID,ALTERNATING,ALTERNATING_BOTH_SIDES}
    record Case(Geometry geometry,int pairs,double spread) {
        String name(){return String.format(Locale.ROOT,"%s-%d@%.2f",geometry.name().toLowerCase(Locale.ROOT),pairs,spread);}
        double generator(int i){return GENERATOR_CENTRE+spread*(GENERATORS[i]-GENERATOR_CENTRE);}
        double tank(int i){return TANK_CENTRE+spread*(TANKS[i]-TANK_CENTRE);}
        static Case full(Geometry geometry){return new Case(geometry,10,1);}
    }
    private record Placement(List<PhysicalFluidTopology.Device> devices,Map<Long,Double> pressures) {}
    private static PhysicalFluidTopology.Device device(long id,int x,int z,Kind kind){
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIMENSION,x,Y,z),kind,PhysicalFluidTopology.Direction.EAST,BLOCK,new FlowControl.Passive());
    }
    /** The blocks of a case, in the layouts of the class comment; generators and tanks carry their pressures. */
    private static Placement place(Case c) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var pressures=new LinkedHashMap<Long,Double>();int n=c.pairs();
        switch(c.geometry()) {
            case GRID->{
                for(int x=0;x<n;x++){devices.add(device(1+x,x,0,Kind.GENERATOR));pressures.put(1L+x,c.generator(x));}
                for(int x=0;x<n;x++)devices.add(device(11+x,x,1,Kind.PIPE));
                for(int x=0;x<n;x++)devices.add(device(21+x,x,2,Kind.PIPE));
                for(int x=0;x<n;x++){devices.add(device(31+x,x,3,Kind.RESERVOIR));pressures.put(31L+x,c.tank(x));}
            }
            case ALTERNATING,ALTERNATING_BOTH_SIDES->{
                for(int x=0;x<2*n;x++){boolean generator=x%2==0;devices.add(device(1+x,x,0,generator?Kind.GENERATOR:Kind.RESERVOIR));pressures.put(1L+x,generator?c.generator(x/2):c.tank(x/2));}
                for(int x=0;x<2*n;x++)devices.add(device(21+x,x,1,Kind.PIPE));
                if(c.geometry()==Geometry.ALTERNATING_BOTH_SIDES)for(int x=0;x<2*n;x++)devices.add(device(41+x,x,-1,Kind.PIPE));
            }
        }
        return new Placement(devices,pressures);
    }
    private static double[] nitrogen(FluidThermodynamics model){double[] n=new double[model.components().size()];n[MaterialTestBasis.NITROGEN]=1;return n;}
    /** The compile of a case's blocks with every boundary freshly initialized, as the world first activates it. */
    private PhysicalFluidTopology.Compiled compiled(Case c,FluidThermodynamics model) {
        var placement=place(c);var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(var d:placement.devices())if(placement.pressures().containsKey(d.id()))
            boundaries.put(d.id(),new FluidDeviceSpec(1,TEMPERATURE,placement.pressures().get(d.id()),nitrogen(model)).initialize(d,model,()->{}));
        return PhysicalFluidTopology.compile(placement.devices(),boundaries);
    }
    /** The one island of a case, its junction holdups sized as the solver's first solve sizes them, so the ledger's
     * initial totals hold them. */
    private PassiveNetwork island(Case c,FluidThermodynamics model) {
        var compiled=compiled(c,model);
        assertEquals(1,compiled.islands().size(),c.name()+": one island expected: "+compiled.diagnostics());
        assertEquals(place(c).devices().size(),compiled.islands().getFirst().physicalIds().size(),c.name()+": every block in it");
        return PassiveNetwork.sizeJunctionHoldups(compiled.islands().getFirst().graph(),model);
    }

    /* ---------------- integration ---------------- */

    /** A committed state every five simulated seconds, for the cadence comparison. */
    private record Frame(double time,double[] tankPressures,double[] tankTemperatures,double[][] tankMoles,double totalSupplied,double[] supplied) {}
    /** One case at one slice length: what it cost, how far it got, and everything an oracle found wrong on the way. */
    private static final class Run {
        Case c;double slice;int intervals,completed,accepted,rejected;long newtonSolves,ms;String failure;
        double worstMoles,worstEnergy,pMax,lowestTankFirstGain,topGeneratorFirstSupply;int monotoneTanks,tankCount,directionsJudged;
        final List<String> violations=new ArrayList<>();final List<Frame> frames=new ArrayList<>();final Map<String,Integer> reasons=new TreeMap<>();
        PassiveNetwork graph;PassiveIntervalSolver.Result last;
        boolean ok(){return failure==null;}
        String line(){
            return String.format(Locale.ROOT,"EXTREME_TOPOLOGY case=%s interval=%s intervals=%d/%d newtonSolves=%d accepted=%d rejected=%d worstMoles=%.3g worstEnergy=%.3g ms=%d%s",
                    c.name(),slice>=1?"5":"0.1",completed,intervals,newtonSolves,accepted,rejected,worstMoles,worstEnergy,ms,failure==null?" monotoneTanks="+monotoneTanks+"/"+tankCount+" directionsJudged="+directionsJudged:" FAILED "+failure);
        }
    }
    private Run integrate(Case c,double slice,int count,FluidThermodynamics model) {
        var run=new Run();run.c=c;run.slice=slice;run.intervals=count;
        var graph=island(c,model);double[] mw=model.molecularWeights();var nodes=graph.reservoirs();
        var tanks=new ArrayList<Integer>();var generators=new ArrayList<Integer>();
        for(int i=0;i<nodes.size();i++){var kind=nodes.get(i).kind();if(kind==PassiveNetwork.NodeKind.RESERVOIR)tanks.add(i);else if(kind==PassiveNetwork.NodeKind.GENERATOR)generators.add(i);}
        run.tankCount=tanks.size();
        run.pMax=generators.stream().mapToDouble(i->nodes.get(i).state().pressure()).max().orElseThrow();
        long top=generators.stream().filter(i->nodes.get(i).state().pressure()==run.pMax).map(i->nodes.get(i).id()).findFirst().orElseThrow();
        int lowest=tanks.stream().min(Comparator.comparingDouble(i->nodes.get(i).state().pressure())).orElseThrow();
        var slot=new HashMap<Long,Integer>();for(int g=0;g<generators.size();g++)slot.put(nodes.get(generators.get(g)).id(),g);
        double[] initial=totals(graph),external=new double[initial.length],supplied=new double[generators.size()];
        double initialEnergy=energy(graph,mw),externalEnergy=0,totalSupplied=0;
        double[][] history=new double[tanks.size()][count+1];for(int t=0;t<tanks.size();t++)history[t][0]=nodes.get(tanks.get(t)).state().pressure();
        double lowestTank=tanks.stream().mapToDouble(i->nodes.get(i).state().pressure()).min().orElseThrow();
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        boolean enabled=SolverDiagnostics.ENABLED;SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
        long start=System.nanoTime();int framesEvery=(int)Math.round(5/slice);
        try {
            for(int i=0;i<count;i++) {
                var before=graph;
                solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
                PassiveIntervalSolver.Result result;
                try{result=solver.solve(graph,slice,PassiveIntervalSolver.Settings.defaults(),()->{});}
                catch(RuntimeException held){run.failure="interval "+(i+1)+" (t="+String.format(Locale.ROOT,"%.1f",slice*i)+" s): "+held;break;}
                String at="interval "+(i+1)+": ";
                if(result.acceptance()!=PassiveStepSolver.Acceptance.FULL||result.advancedSeconds()!=slice)run.violations.add(at+"not a full interval: "+result.acceptance()+" advanced "+result.advancedSeconds());
                committed=result;graph=result.graph();run.completed++;run.accepted+=result.acceptedSubsteps();run.rejected+=result.rejectedSubsteps();
                result.rejectionReasons().forEach((k,v)->run.reasons.merge(k,v,Integer::sum));
                double[] intervalSupply=new double[generators.size()];
                for(var transfer:result.boundaries()) {
                    var n=transfer.moles();double mass=0;
                    for(int k=0;k<n.length;k++){external[k]+=n[k];mass+=n[k]*mw[k];}
                    externalEnergy+=transfer.totalEnergyJoule();
                    Integer g=slot.get(transfer.nodeId());
                    if(g==null){run.violations.add(at+"a boundary transfer at "+transfer.nodeId()+", which is not a generator");continue;}
                    supplied[g]+=mass;intervalSupply[g]+=mass;totalSupplied+=mass;
                }
                for(int g=0;g<generators.size();g++)if(intervalSupply[g]<-ZERO_FLOW*slice)
                    run.violations.add(at+"generator "+nodes.get(generators.get(g)).id()+" received "+(-intervalSupply[g])+" kg");
                // The ledger: tanks plus junction holdups against what the generators delivered.
                var sum=totals(graph);
                for(int k=0;k<sum.length;k++){
                    double error=Math.abs(sum[k]-initial[k]-external[k])/Math.max(1,initial[k]);run.worstMoles=Math.max(run.worstMoles,error);
                    if(error>=COMPONENT_LEDGER)run.violations.add(at+"component "+k+" ledger "+error);
                }
                double energyError=Math.abs(energy(graph,mw)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));
                run.worstEnergy=Math.max(run.worstEnergy,energyError);if(energyError>=ENERGY_LEDGER)run.violations.add(at+"energy ledger "+energyError);
                var now=graph.reservoirs();double min=Double.POSITIVE_INFINITY;
                for(int t=0;t<tanks.size();t++) {
                    double p=now.get(tanks.get(t)).state().pressure();history[t][i+1]=p;min=Math.min(min,p);
                    if(p>run.pMax*(1+1e-9))run.violations.add(at+"tank "+now.get(tanks.get(t)).id()+" at "+p+" Pa, above the highest generator");
                }
                if(min<lowestTank*(1-1e-9))run.violations.add(at+"the lowest tank fell from "+lowestTank+" to "+min+" Pa");
                lowestTank=min;
                if(i==0) {
                    run.lowestTankFirstGain=mass(now.get(lowest),mw)-mass(nodes.get(lowest),mw);
                    run.topGeneratorFirstSupply=intervalSupply[slot.get(top)];
                }
                // Interval 1 starts from the compiler's junction guess, not a state, so directions are judged from interval 2.
                if(i>0){var judged=new int[1];for(var error:directionErrors(before,graph,result,judged))run.violations.add(at+error);run.directionsJudged+=judged[0];}
                if((i+1)%framesEvery==0) {
                    double[] p=new double[tanks.size()],temperature=new double[tanks.size()];double[][] n=new double[tanks.size()][];
                    for(int t=0;t<tanks.size();t++){var s=now.get(tanks.get(t));p[t]=s.state().pressure();temperature[t]=s.state().temperature();n[t]=s.inventory().moles();}
                    run.frames.add(new Frame((i+1)*slice,p,temperature,n,totalSupplied,supplied.clone()));
                }
            }
        } finally {
            run.newtonSolves=SolverDiagnostics.sample().value("newtonSolves");SolverDiagnostics.ENABLED=enabled;SolverDiagnostics.reset();
            run.ms=(System.nanoTime()-start)/1000000;
        }
        run.graph=graph;run.last=committed;
        for(double[] h:history){boolean monotone=true;for(int k=1;k<=run.completed;k++)if(h[k]<h[k-1]*(1-1e-9))monotone=false;if(monotone)run.monotoneTanks++;}
        return run;
    }
    /**
     * The direction oracle on every boundary connection, judged where the pressures agree over the whole interval: a
     * boundary above its junction by more than {@link #DIRECTION_MARGIN} at both ends of the interval must have sent fluid
     * out over it (a generator supplies, a tank discharges); one below it at both ends must have taken none out (a tank
     * receives, a generator is closed to within the solver's zero-flow threshold). Where the sign changes within the
     * interval the average may have either sign, so no statement is made. {@code judged} counts the statements made.
     */
    private static List<String> directionErrors(PassiveNetwork before,PassiveNetwork after,PassiveIntervalSolver.Result result,int[] judged) {
        var errors=new ArrayList<String>();double[] flows=result.averageMassFlows();
        for(int i=0;i<flows.length;i++) {
            var pipe=after.pipes().get(i);var a=after.reservoirs().get(pipe.first());var b=after.reservoirs().get(pipe.second());
            if(a.junction()==b.junction())continue;
            int boundary=a.junction()?pipe.second():pipe.first(),junction=a.junction()?pipe.first():pipe.second();
            double out=a.junction()?-flows[i]:flows[i];
            double startDifference=before.reservoirs().get(boundary).state().pressure()-before.reservoirs().get(junction).state().pressure();
            double endDifference=after.reservoirs().get(boundary).state().pressure()-after.reservoirs().get(junction).state().pressure();
            var node=after.reservoirs().get(boundary);
            String what=String.format(Locale.ROOT,"%s %d %.1f Pa over its junction (start %.1f Pa), outflow %.4g kg/s",node.kind(),node.id(),endDifference,startDifference,out);
            if(Math.min(startDifference,endDifference)>DIRECTION_MARGIN||Math.max(startDifference,endDifference)<-DIRECTION_MARGIN)judged[0]++;
            if(startDifference>DIRECTION_MARGIN&&endDifference>DIRECTION_MARGIN&&!(out>0))errors.add(what+": above its junction but sent nothing");
            if(startDifference<-DIRECTION_MARGIN&&endDifference<-DIRECTION_MARGIN) {
                if(node.kind()==PassiveNetwork.NodeKind.GENERATOR&&out>ZERO_FLOW)errors.add(what+": a generator below its junction supplied");
                if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&!(out<0))errors.add(what+": a tank below its junction did not receive");
            }
        }
        return errors;
    }
    private static double mass(PassiveNetwork.Reservoir node,double[] mw){double m=0;var n=node.inventory().moles();for(int c=0;c<n.length;c++)m+=mw[c]*n[c];return m;}
    /** Tanks and junction holdups: the finite inventory of the island. */
    private static double[] totals(PassiveNetwork graph) {
        double[] totals=null;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction()){
            var n=node.inventory().moles();if(totals==null)totals=new double[n.length];for(int c=0;c<n.length;c++)totals[c]+=n[c];
        }
        return totals;
    }
    /** A junction's owned inventory (energy field m h) is counted like a tank's, as in MixedGasJunctionTransientTest. */
    private static double energy(PassiveNetwork graph,double[] mw) {
        double energy=0;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction())
            energy+=node.inventory().internalEnergy()+mass(node,mw)*PassiveStepSolver.GRAVITY*node.elevation();
        return energy;
    }

    /* ---------------- the oracles ---------------- */

    /** Forty 5 s intervals and four hundred 0.1 s intervals of one case, and every oracle of the class comment on both. */
    private void assertIntegrates(Case c,FluidThermodynamics model,String label) {
        var coarse=integrate(c,5,40,model);System.out.println(label+coarse.line());
        var fine=integrate(c,.1,400,model);System.out.println(label+fine.line());
        for(var run:List.of(coarse,fine)) {
            String name=c.name()+" at "+run.slice+" s";
            assertTrue(run.ok(),name+": "+run.failure);
            assertTrue(run.violations.isEmpty(),name+": "+run.violations.size()+" oracle failures, first "+run.violations.subList(0,Math.min(5,run.violations.size())));
            assertTrue(run.worstMoles<COMPONENT_LEDGER&&run.worstEnergy<ENERGY_LEDGER,name+": ledgers "+run.worstMoles+" / "+run.worstEnergy);
            // At rest: every tank at the highest generator, every other generator closed.
            for(var node:run.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR)
                assertEquals(run.pMax,node.state().pressure(),REST*run.pMax,name+": tank "+node.id()+" must end at the highest generator");
            for(int i=0;i<run.graph.pipes().size();i++) {
                var pipe=run.graph.pipes().get(i);
                for(int end:new int[]{pipe.first(),pipe.second()}) {
                    var node=run.graph.reservoirs().get(end);
                    if(node.kind()==PassiveNetwork.NodeKind.GENERATOR&&node.state().pressure()<run.pMax-DIRECTION_MARGIN)
                        assertEquals(FlowControl.Mode.CLOSED,run.last.endpointModes().get(i),name+": generator "+node.id()+" below the band must end closed");
                }
            }
        }
        // The first 0.1 s: the generator above every other node supplies and the lowest tank receives.
        assertTrue(fine.topGeneratorFirstSupply>0,c.name()+": the highest generator must supply at once: "+fine.topGeneratorFirstSupply);
        assertTrue(fine.lowestTankFirstGain>0,c.name()+": the lowest tank must receive at once: "+fine.lowestTankFirstGain);
        System.out.println(label+assertCadence(coarse,fine));
    }
    /** The 5 s and 0.1 s trajectories at the common times 5, 10, ..., 40 s. Returns the measured line. */
    private static String assertCadence(Run coarse,Run fine) {
        double pressure=0,temperature=0,moles=0,total=0,split=0;int compared=0;
        for(var a:coarse.frames) {
            var match=fine.frames.stream().filter(f->Math.abs(f.time()-a.time())<1e-6).findFirst();
            if(match.isEmpty())continue;var b=match.get();compared++;
            for(int t=0;t<a.tankPressures().length;t++) {
                double dp=Math.abs(a.tankPressures()[t]-b.tankPressures()[t]);pressure=Math.max(pressure,dp/b.tankPressures()[t]);
                assertTrue(dp<=1+CADENCE_TOLERANCE*b.tankPressures()[t],coarse.c.name()+" t="+a.time()+" tank "+t+" pressure "+a.tankPressures()[t]+" against "+b.tankPressures()[t]);
                double dT=Math.abs(a.tankTemperatures()[t]-b.tankTemperatures()[t]);temperature=Math.max(temperature,dT);
                assertTrue(dT<=CADENCE_TEMPERATURE,coarse.c.name()+" t="+a.time()+" tank "+t+" temperature "+a.tankTemperatures()[t]+" against "+b.tankTemperatures()[t]);
                for(int k=0;k<a.tankMoles()[t].length;k++) {
                    double target=b.tankMoles()[t][k],dn=Math.abs(a.tankMoles()[t][k]-target);if(target!=0)moles=Math.max(moles,dn/Math.abs(target));
                    assertTrue(dn<=1e-10+CADENCE_TOLERANCE*Math.abs(target),coarse.c.name()+" t="+a.time()+" tank "+t+" component "+k);
                }
            }
            double dm=Math.abs(a.totalSupplied()-b.totalSupplied());total=Math.max(total,dm/b.totalSupplied());
            assertTrue(dm<=1e-8+CADENCE_TOLERANCE*b.totalSupplied(),coarse.c.name()+" t="+a.time()+" supplied "+a.totalSupplied()+" against "+b.totalSupplied());
            for(int g=0;g<a.supplied().length;g++)split=Math.max(split,Math.abs(a.supplied()[g]-b.supplied()[g])/b.totalSupplied());
        }
        assertEquals(8,compared,"5, 10, ..., 40 s");
        // Not asserted: how the supply splits between generators a few kPa apart (measured up to 3.2 % of the total at spread 0.2).
        return String.format(Locale.ROOT,"EXTREME_TOPOLOGY_CADENCE case=%s pressure=%.3g temperature=%.3g K moles=%.3g supplied=%.3g generatorSplit=%.3g",
                coarse.c.name(),pressure,temperature,moles,total,split);
    }
    /** The drop at which the default cap saturates a compiled connection of {@code blocks} pipe blocks (a generator or tank
     * stub is half a block), in nitrogen at 350 K and {@code pressure}. */
    private double capDrop(double pressure,double blocks) {
        var state=defaultModel.flashTP(TEMPERATURE,pressure,nitrogen(defaultModel),()->{});
        double density=state.mass()/state.volume(),viscosity=defaultModel.viscosity.vapor(TEMPERATURE,state.vaporView(),state.waterVapor());
        var pipe=new PassiveNetwork.Pipe(1,0,1,new PipeResistance.Geometry(blocks,.05,.000045,0));
        return pipe.pressureDrop(density*pipe.minimumArea()*defaultModel.velocityLimit(state),density,viscosity);
    }

    /* ---------------- the tests ---------------- */

    @Test void eachFullFixtureCompilesToTheIslandTheCompilerRulesPredict() {
        record Expected(int nodes,int junctions,int pipes) {}
        var expected=Map.of(Geometry.GRID,new Expected(40,20,48),Geometry.ALTERNATING,new Expected(38,18,37),Geometry.ALTERNATING_BOTH_SIDES,new Expected(56,36,74));
        for(var geometry:Geometry.values()) {
            var c=Case.full(geometry);var compiled=compiled(c,defaultModel);
            assertEquals(1,compiled.islands().size(),c.name());
            assertTrue(compiled.diagnostics().isEmpty(),c.name()+": no dead end or unconnected block: "+compiled.diagnostics());
            var island=compiled.islands().getFirst();var graph=island.graph();
            assertEquals(place(c).devices().size(),island.physicalIds().size(),c.name()+": every block in the island");
            assertEquals(10,graph.reservoirs().stream().filter(n->n.kind()==PassiveNetwork.NodeKind.GENERATOR).count(),c.name());
            assertEquals(10,graph.reservoirs().stream().filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).count(),c.name());
            var e=expected.get(geometry);
            assertEquals(e.nodes(),graph.reservoirs().size(),c.name()+" nodes");
            assertEquals(e.junctions(),graph.reservoirs().stream().filter(PassiveNetwork.Reservoir::junction).count(),c.name()+" junctions");
            assertEquals(e.pipes(),graph.pipes().size(),c.name()+" connections");
            // Every boundary is a stub onto a junction; no two boundaries share a connection.
            for(var pipe:graph.pipes())assertTrue(graph.reservoirs().get(pipe.first()).junction()||graph.reservoirs().get(pipe.second()).junction(),c.name());
            System.out.println("EXTREME_TOPOLOGY_STRUCTURE case="+c.name()+" blocks="+island.physicalIds().size()+" nodes="+graph.reservoirs().size()
                    +" junctions="+e.junctions()+" connections="+graph.pipes().size());
        }
    }

    /**
     * The full fixtures at the default cap: the regression of decision D12. Forty 5 s and four hundred 0.1 s intervals of
     * each, every interval a full accepted interval, with every oracle of {@link #assertIntegrates} (ledgers, no generator
     * receiving, the tank bounds and the monotone lowest tank, directions, rest, cadence).
     *
     * <p>History: written while the defect was open. With {@link #FULL_CASES_OPEN_DEFECT} true it reproduced the open
     * defect of the class comment exactly - the first interval, at both slice lengths, failing in pass 0 of the step's
     * active set - and was to fail once the solver integrated them; decision D12 made it fail as intended, and the flag was
     * set false, so the same test runs every oracle instead. The reproduction branch is kept for a tree without D12.
     */
    @ParameterizedTest @EnumSource(Geometry.class)
    void theFullCasesHoldOnTheirFirstIntervalOpenDefect(Geometry geometry) {
        var c=Case.full(geometry);
        if(!FULL_CASES_OPEN_DEFECT){assertIntegrates(c,defaultModel,"");return;}
        System.out.println(String.format(Locale.ROOT,"EXTREME_TOPOLOGY_CAP nitrogen 350 K 150 kPa: saturates at %.0f Pa over a half-block stub, %.0f Pa over a one-block run",
                capDrop(150e3,.5),capDrop(150e3,1)));
        for(double slice:new double[]{5,.1}) {
            var run=integrate(c,slice,slice>=1?40:400,defaultModel);
            System.out.println("OPEN DEFECT "+run.line());
            assertEquals(0,run.completed,c.name()+" at "+slice+" s: expected to hold on its first interval (open defect); it now integrates "
                    +run.completed+" intervals, so set FULL_CASES_OPEN_DEFECT=false: "+run.line());
            assertTrue(run.failure.startsWith("interval 1 ")&&run.failure.contains("Nonconvergence")&&run.failure.contains("active-set pass=0"),
                    c.name()+" at "+slice+" s: a different failure than the recorded pass-0 hold: "+run.failure);
        }
    }

    /** The mechanism check: the full fixtures and pressures integrate every interval, with every oracle, when the velocity
     * cap is the configuration's largest (the acoustic bound then caps, about ten times the drop). */
    @ParameterizedTest @EnumSource(Geometry.class)
    void theFullCasesIntegrateWhenTheVelocityCapDoesNotSaturate(Geometry geometry) {
        assertIntegrates(Case.full(geometry),uncappedModel,"[maximumVelocity="+(long)LARGEST_CONFIGURED_VELOCITY+"] ");
    }

    /** The reduction at the default cap: the same fixtures with every pressure's distance from the centres scaled to a
     * fifth (generators 146 to 164 kPa, tanks 136 to 154 kPa), the largest spread at which all three pass at both slices. */
    @ParameterizedTest @EnumSource(Geometry.class)
    void theCasesAtAFifthOfTheSpreadIntegrate(Geometry geometry) {
        assertIntegrates(new Case(geometry,10,REDUCED_SPREAD),defaultModel,"");
    }
}

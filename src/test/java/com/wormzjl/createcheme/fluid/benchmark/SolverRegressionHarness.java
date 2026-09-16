package com.wormzjl.createcheme.fluid.benchmark;

import com.google.gson.*;
import com.wormzjl.createcheme.runtime.fluid.FluidCheckpointCodec;
import com.wormzjl.createcheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.createcheme.runtime.fluid.FluidSavedData;
import com.wormzjl.createcheme.runtime.fluid.RetainedSolver;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Saved-island replay harness for the solver optimization work packages: it re-solves fixed
 * fixtures, records cost (wall time, allocation, {@link SolverDiagnostics} counters) and compares
 * the resulting trajectory against committed reference states.
 *
 * <p>Fixtures come from a stress-world snapshot resolved as: the system property or environment
 * variable {@code fluid.regression.snapshot} (a directory, or the primary {@code core.dat} file
 * itself), otherwise {@code build/probe/core.dat} and {@code build/probe/core-fallback.dat}
 * relative to the project directory. Those files are build output and are not committed; a missing
 * snapshot skips its fixtures instead of failing.
 *
 * <p>References live in {@code src/test/resources/fluid/regression} and were captured on
 * 154007d before any solver change. Regenerate with {@code -Dfluid.regression.capture=true}.
 */
final class SolverRegressionHarness {
    static final String SNAPSHOT_PROPERTY="fluid.regression.snapshot";
    static final Path DEFAULT_DIRECTORY=Path.of("build","probe");
    static final Path REFERENCES=Path.of("src","test","resources","fluid","regression");
    static final Path REPORT=Path.of("build","reports","fluid","solver-optimization.json");
    private static final com.sun.management.ThreadMXBean THREADS=
            (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static final Runnable NOOP=()->{};

    private SolverRegressionHarness() {}

    /** Bitwise agreement, or agreement inside declared per-quantity tolerances. */
    sealed interface Tolerance permits Exact,Relative {}
    record Exact() implements Tolerance {}
    /** {@code state} and {@code moles} are relative, {@code temperature} absolute in kelvin,
     * {@code phaseFraction} absolute on a volume fraction, {@code flow} relative to max(|q|,1e-6).
     * A flow also passes within {@code flowAbsolute} kg/s, which is how the interval controller
     * itself treats near-zero pipes (its own declared numerical floor is 1e-9 + 2e-9 m/dt kg/s);
     * pass 0 for a purely relative flow gate. */
    record Relative(double state,double temperature,double phaseFraction,double flow,double flowAbsolute) implements Tolerance {}

    /** {@code warmup} intervals are replayed from {@code start} and discarded before the measured
     * ones restart from {@code start}, so the recorded wall times are not a JIT transient. */
    record Fixture(String name,String description,PassiveNetwork start,int warmup,int intervals,Tolerance tolerance) {}
    record NodeState(long id,double temperature,double pressure,double mass,double volume,
                     double liquidVolume,double vaporVolume,double waterVolume,double[] moles) {}
    record IntervalState(int accepted,int rejected,List<NodeState> nodes,double[] flows) {}
    record IntervalReport(String fixture,int index,double wallMilliseconds,long allocatedBytes,
                          IntervalState state,SolverDiagnostics.Sample diagnostics) {}

    static FluidThermodynamics model() {
        return FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
    }

    // ---- snapshot resolution -------------------------------------------------------------

    private static Optional<Path> configured() {
        String value=System.getProperty(SNAPSHOT_PROPERTY);
        if(value==null||value.isBlank())value=System.getenv(SNAPSHOT_PROPERTY);
        if(value==null||value.isBlank())value=System.getenv("FLUID_REGRESSION_SNAPSHOT");
        return value==null||value.isBlank()?Optional.empty():Optional.of(Path.of(value));
    }
    /** {@code name} is the conventional file name ({@code core.dat} / {@code core-fallback.dat}). */
    static Optional<Path> snapshot(String name) {
        var override=configured();
        Path candidate;
        if(override.isEmpty())candidate=DEFAULT_DIRECTORY.resolve(name);
        else if(Files.isDirectory(override.get()))candidate=override.get().resolve(name);
        else candidate=name.equals("core.dat")?override.get():override.get().resolveSibling(name);
        return Files.isReadable(candidate)?Optional.of(candidate):Optional.empty();
    }
    /** Every island of one snapshot, by island id. */
    static Map<Long,PassiveNetwork> islands(FluidThermodynamics model,Path path) {
        try {
            var tag=NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap()).getCompound("data");
            var checkpoint=FluidSavedData.load(tag,key->model).checkpoint();
            var result=new LinkedHashMap<Long,PassiveNetwork>();
            for(FluidCheckpointCodec.IslandEntry entry:checkpoint.islands())result.put(entry.snapshot().id(),entry.snapshot().graph());
            return result;
        }catch(java.io.IOException failure){throw new IllegalStateException("Cannot read "+path,failure);}
    }
    /** The 100-reservoir cosine-pressure chain of {@link FluidNetworkBenchmarkTest}. */
    static PassiveNetwork cosineChain(FluidThermodynamics model,int count) {
        String id=FluidPresetCatalog.NETWORK_PACKAGE;
        var composition=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(id)
                .crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),22);
        composition[20]=.1;composition[21]=.2;
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(int i=0;i<count;i++) {
            double pressure=150000+1000*Math.cos(i*.7);var n=composition.clone();var unit=model.flashTP(350,pressure,n,NOOP);
            for(int c=0;c<n.length;c++)n[c]/=unit.volume();var state=model.flashTP(350,pressure,n,NOOP);
            nodes.add(new PassiveNetwork.Reservoir(i+1,0,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,n,state.internalEnergy())));
            if(i>0)pipes.add(new PassiveNetwork.Pipe(i,i-1,i,new PipeResistance.Geometry(100,.05,.000045,0)));
        }
        return new PassiveNetwork(nodes,pipes);
    }

    // ---- replay --------------------------------------------------------------------------

    /** Solves the fixture's consecutive five-second intervals through one per-island retained
     * solver handle, exactly as a coordinator-dispatched island job does, feeding the accepted
     * graph forward. The warm-up pass uses its own handle, so the measured pass still starts cold. */
    static List<IntervalReport> replay(FluidThermodynamics model,Fixture fixture) {
        var warm=fixture.start();var warmupSolver=new RetainedSolver();
        for(int interval=0;interval<fixture.warmup();interval++)
            warm=warmupSolver.solve(model,warm,5,PassiveIntervalSolver.Settings.defaults(),NOOP).graph();
        var reports=new ArrayList<IntervalReport>();var graph=fixture.start();var retained=new RetainedSolver();
        for(int interval=0;interval<fixture.intervals();interval++) {
            SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
            long allocated=THREADS.getCurrentThreadAllocatedBytes(),started=System.nanoTime();
            PassiveIntervalSolver.Result result;
            try{result=retained.solve(model,graph,5,PassiveIntervalSolver.Settings.defaults(),NOOP);}
            finally{SolverDiagnostics.ENABLED=false;}
            double milliseconds=(System.nanoTime()-started)/1e6;
            long bytes=THREADS.getCurrentThreadAllocatedBytes()-allocated;
            graph=result.graph();
            reports.add(new IntervalReport(fixture.name(),interval,milliseconds,bytes,capture(result),SolverDiagnostics.sample()));
        }
        return List.copyOf(reports);
    }
    static IntervalState capture(PassiveIntervalSolver.Result result) {
        var nodes=new ArrayList<NodeState>();
        for(var reservoir:result.graph().reservoirs()) {
            var state=reservoir.state();
            nodes.add(new NodeState(reservoir.id(),state.temperature(),state.pressure(),state.mass(),state.volume(),
                    state.liquidVolume(),state.vaporVolume(),state.waterVolume(),PhaseLayout.totalAmounts(state)));
        }
        return new IntervalState(result.acceptedSubsteps(),result.rejectedSubsteps(),List.copyOf(nodes),result.averageMassFlows());
    }

    // ---- reference JSON ------------------------------------------------------------------

    /** {@code -Dfluid.regression.references=<dir>} compares against (or captures into) another
     * baseline set, which is how a later work package proves it is bitwise neutral against the
     * commit before it rather than against the original 154007d capture. */
    static Path reference(String fixture) {
        String directory=System.getProperty("fluid.regression.references");
        return (directory==null||directory.isBlank()?REFERENCES:Path.of(directory)).resolve(fixture+".json");
    }
    static JsonObject encode(String fixture,List<IntervalReport> reports) {
        var root=new JsonObject();root.addProperty("fixture",fixture);
        var intervals=new JsonArray();
        for(var report:reports) {
            var entry=new JsonObject();var state=report.state();
            entry.addProperty("interval",report.index());
            entry.addProperty("accepted",state.accepted());entry.addProperty("rejected",state.rejected());
            var nodes=new JsonArray();
            for(var node:state.nodes()) {
                var item=new JsonObject();item.addProperty("id",node.id());
                item.addProperty("temperature",node.temperature());item.addProperty("pressure",node.pressure());
                item.addProperty("mass",node.mass());item.addProperty("volume",node.volume());
                item.addProperty("liquidVolume",node.liquidVolume());item.addProperty("vaporVolume",node.vaporVolume());
                item.addProperty("waterVolume",node.waterVolume());item.add("moles",numbers(node.moles()));
                nodes.add(item);
            }
            entry.add("nodes",nodes);entry.add("flows",numbers(state.flows()));
            intervals.add(entry);
        }
        root.add("intervals",intervals);return root;
    }
    private static JsonArray numbers(double[] values) {
        var array=new JsonArray(values.length);for(double value:values)array.add(value);return array;
    }
    static void write(Path path,JsonObject root) {
        try {
            Files.createDirectories(path.getParent());
            // Compact: these are machine baselines, and Double.toString round-trips exactly.
            Files.writeString(path,new Gson().toJson(root)+"\n");
        }catch(java.io.IOException failure){throw new IllegalStateException("Cannot write "+path,failure);}
    }
    static JsonObject read(Path path) {
        try{return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot read "+path,failure);}
    }

    // ---- comparison ----------------------------------------------------------------------

    /** Disagreements against the declared tolerance, plus the largest deviation actually seen in
     * each quantity, which is reported whether or not the gate passes. */
    record Comparison(List<String> failures,double state,double temperature,double phaseFraction,double flow,boolean substepsDiffer,
                      String stateAt,String temperatureAt,String phaseAt,String flowAt) {
        String deviations() {
            return String.format(Locale.ROOT,"max deviation: state/moles %.3e relative (%s), temperature %.3e K (%s), "
                            +"phase fraction %.3e (%s), flow %.3e relative (%s)%s",
                    state,stateAt,temperature,temperatureAt,phaseFraction,phaseAt,flow,flowAt,substepsDiffer?"; substep counts differ":"");
        }
    }
    private static final class Worst {
        private double state,temperature,phaseFraction,flow;
        private String stateAt="-",temperatureAt="-",phaseAt="-",flowAt="-";
        private static String where(String at,String field,double expected,double actual) {
            return at+" "+field+" reference "+expected+" -> "+actual;
        }
    }

    static Comparison compare(String fixture,JsonObject reference,List<IntervalReport> actual,Tolerance tolerance) {
        var failures=new ArrayList<String>();var worst=new Worst();boolean substeps=false;
        var intervals=reference.getAsJsonArray("intervals");
        if(intervals.size()!=actual.size()) {
            failures.add(fixture+": reference has "+intervals.size()+" intervals, replay produced "+actual.size());
            return new Comparison(failures,0,0,0,0,true,"-","-","-","-");
        }
        for(int i=0;i<actual.size();i++) {
            var expected=intervals.get(i).getAsJsonObject();var state=actual.get(i).state();String where=fixture+" interval "+i;
            boolean differ=expected.get("accepted").getAsInt()!=state.accepted()||expected.get("rejected").getAsInt()!=state.rejected();
            substeps|=differ;
            if(differ&&tolerance instanceof Exact)failures.add(where+": substeps "+expected.get("accepted").getAsInt()+"/"
                    +expected.get("rejected").getAsInt()+" -> "+state.accepted()+"/"+state.rejected());
            var nodes=expected.getAsJsonArray("nodes");
            if(nodes.size()!=state.nodes().size()){failures.add(where+": node count "+nodes.size()+" -> "+state.nodes().size());continue;}
            for(int n=0;n<nodes.size();n++) {
                var node=nodes.get(n).getAsJsonObject();var got=state.nodes().get(n);String at=where+" node "+got.id();
                if(node.get("id").getAsLong()!=got.id()){failures.add(at+": identity "+node.get("id").getAsLong());continue;}
                double volume=node.get("volume").getAsDouble();
                check(failures,worst,at,"temperature",node.get("temperature").getAsDouble(),got.temperature(),tolerance,Quantity.TEMPERATURE,0);
                check(failures,worst,at,"pressure",node.get("pressure").getAsDouble(),got.pressure(),tolerance,Quantity.STATE,0);
                check(failures,worst,at,"mass",node.get("mass").getAsDouble(),got.mass(),tolerance,Quantity.STATE,0);
                check(failures,worst,at,"volume",volume,got.volume(),tolerance,Quantity.STATE,0);
                fraction(failures,worst,at,"liquidVolume",node.get("liquidVolume").getAsDouble(),got.liquidVolume(),volume,got.volume(),tolerance);
                fraction(failures,worst,at,"vaporVolume",node.get("vaporVolume").getAsDouble(),got.vaporVolume(),volume,got.volume(),tolerance);
                fraction(failures,worst,at,"waterVolume",node.get("waterVolume").getAsDouble(),got.waterVolume(),volume,got.volume(),tolerance);
                var moles=node.getAsJsonArray("moles");
                if(moles.size()!=got.moles().length){failures.add(at+": component count "+moles.size()+" -> "+got.moles().length);continue;}
                double total=0;for(var value:moles)total+=Math.abs(value.getAsDouble());
                for(int c=0;c<moles.size();c++)
                    check(failures,worst,at,"moles["+c+"]",moles.get(c).getAsDouble(),got.moles()[c],tolerance,Quantity.STATE,1e-9*total);
            }
            var flows=expected.getAsJsonArray("flows");
            if(flows.size()!=state.flows().length){failures.add(where+": pipe count "+flows.size()+" -> "+state.flows().length);continue;}
            for(int e=0;e<flows.size();e++)
                check(failures,worst,where+" pipe "+e,"averageMassFlow",flows.get(e).getAsDouble(),state.flows()[e],tolerance,Quantity.FLOW,0);
        }
        return new Comparison(List.copyOf(failures),worst.state,worst.temperature,worst.phaseFraction,worst.flow,substeps,worst.stateAt,worst.temperatureAt,worst.phaseAt,worst.flowAt);
    }
    private enum Quantity {STATE,TEMPERATURE,FLOW}
    private static void check(List<String> failures,Worst worst,String at,String field,double expected,double actual,
                              Tolerance tolerance,Quantity quantity,double floor) {
        if(Double.doubleToLongBits(expected)==Double.doubleToLongBits(actual))return;
        double difference=Math.abs(expected-actual);
        double scaled=switch(quantity) {
            case TEMPERATURE->difference;
            case FLOW->difference/Math.max(Math.abs(expected),1e-6);
            case STATE->difference/Math.max(Math.abs(expected),Math.max(floor,Double.MIN_NORMAL));
        };
        switch(quantity) {
            case TEMPERATURE->{if(scaled>worst.temperature){worst.temperature=scaled;worst.temperatureAt=Worst.where(at,field,expected,actual);}}
            case FLOW->{if(scaled>worst.flow){worst.flow=scaled;worst.flowAt=Worst.where(at,field,expected,actual);}}
            case STATE->{if(scaled>worst.state){worst.state=scaled;worst.stateAt=Worst.where(at,field,expected,actual);}}
        }
        if(tolerance instanceof Relative relative) {
            if(quantity==Quantity.FLOW&&difference<=relative.flowAbsolute())return;
            double allowed=switch(quantity){case TEMPERATURE->relative.temperature();case FLOW->relative.flow();case STATE->relative.state();};
            if(scaled<=allowed)return;
            if(failures.size()<40)failures.add(at+" "+field+": expected "+expected+", got "+actual+" (scaled deviation "+scaled+" > "+allowed+")");
            return;
        }
        if(failures.size()<40)failures.add(at+" "+field+": expected "+expected+", got "+actual+" (difference "+difference+")");
    }
    private static void fraction(List<String> failures,Worst worst,String at,String field,double expected,double actual,
                                 double expectedVolume,double actualVolume,Tolerance tolerance) {
        if(Double.doubleToLongBits(expected)==Double.doubleToLongBits(actual))return;
        double difference=Math.abs(expected/expectedVolume-actual/actualVolume);
        if(difference>worst.phaseFraction){worst.phaseFraction=difference;worst.phaseAt=Worst.where(at,field,expected,actual);}
        if(tolerance instanceof Relative relative) {
            if(difference<=relative.phaseFraction())return;
            if(failures.size()<40)failures.add(at+" "+field+" fraction: expected "+expected/expectedVolume+", got "+actual/actualVolume
                    +" (difference "+difference+" > "+relative.phaseFraction()+")");
            return;
        }
        if(failures.size()<40)failures.add(at+" "+field+": expected "+expected+", got "+actual+" (difference "+Math.abs(expected-actual)+")");
    }
}

package com.wormzjl.createcheme.fluid.benchmark;

import com.google.gson.*;
import com.wormzjl.createcheme.runtime.fluid.FluidCheckpointCodec;
import com.wormzjl.createcheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.createcheme.runtime.fluid.FluidSavedData;
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
     * {@code phaseFraction} absolute on a volume fraction, {@code flow} relative to max(|q|,1e-6). */
    record Relative(double state,double temperature,double phaseFraction,double flow) implements Tolerance {}

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

    /** Solves the fixture's consecutive five-second intervals, feeding the accepted graph forward. */
    static List<IntervalReport> replay(FluidThermodynamics model,Fixture fixture) {
        var warm=fixture.start();
        for(int interval=0;interval<fixture.warmup();interval++)
            warm=new PassiveIntervalSolver(model).solve(warm,5,PassiveIntervalSolver.Settings.defaults(),NOOP).graph();
        var reports=new ArrayList<IntervalReport>();var graph=fixture.start();
        for(int interval=0;interval<fixture.intervals();interval++) {
            SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
            long allocated=THREADS.getCurrentThreadAllocatedBytes(),started=System.nanoTime();
            PassiveIntervalSolver.Result result;
            try{result=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),NOOP);}
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

    static Path reference(String fixture){return REFERENCES.resolve(fixture+".json");}
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

    /** Returns one message per disagreement, empty when the replay matches the reference. */
    static List<String> compare(String fixture,JsonObject reference,List<IntervalReport> actual,Tolerance tolerance) {
        var failures=new ArrayList<String>();
        var intervals=reference.getAsJsonArray("intervals");
        if(intervals.size()!=actual.size()) {
            failures.add(fixture+": reference has "+intervals.size()+" intervals, replay produced "+actual.size());
            return failures;
        }
        for(int i=0;i<actual.size()&&failures.size()<40;i++) {
            var expected=intervals.get(i).getAsJsonObject();var state=actual.get(i).state();String where=fixture+" interval "+i;
            if(tolerance instanceof Exact) {
                if(expected.get("accepted").getAsInt()!=state.accepted())
                    failures.add(where+": accepted substeps "+expected.get("accepted").getAsInt()+" -> "+state.accepted());
                if(expected.get("rejected").getAsInt()!=state.rejected())
                    failures.add(where+": rejected substeps "+expected.get("rejected").getAsInt()+" -> "+state.rejected());
            }
            var nodes=expected.getAsJsonArray("nodes");
            if(nodes.size()!=state.nodes().size()){failures.add(where+": node count "+nodes.size()+" -> "+state.nodes().size());continue;}
            for(int n=0;n<nodes.size()&&failures.size()<40;n++) {
                var node=nodes.get(n).getAsJsonObject();var got=state.nodes().get(n);String at=where+" node "+got.id();
                if(node.get("id").getAsLong()!=got.id()){failures.add(at+": identity "+node.get("id").getAsLong());continue;}
                check(failures,at,"temperature",node.get("temperature").getAsDouble(),got.temperature(),tolerance,Quantity.TEMPERATURE,0);
                check(failures,at,"pressure",node.get("pressure").getAsDouble(),got.pressure(),tolerance,Quantity.STATE,0);
                check(failures,at,"mass",node.get("mass").getAsDouble(),got.mass(),tolerance,Quantity.STATE,0);
                check(failures,at,"volume",node.get("volume").getAsDouble(),got.volume(),tolerance,Quantity.STATE,0);
                double volume=node.get("volume").getAsDouble();
                fraction(failures,at,"liquidVolume",node.get("liquidVolume").getAsDouble(),got.liquidVolume(),volume,got.volume(),tolerance);
                fraction(failures,at,"vaporVolume",node.get("vaporVolume").getAsDouble(),got.vaporVolume(),volume,got.volume(),tolerance);
                fraction(failures,at,"waterVolume",node.get("waterVolume").getAsDouble(),got.waterVolume(),volume,got.volume(),tolerance);
                var moles=node.getAsJsonArray("moles");
                if(moles.size()!=got.moles().length){failures.add(at+": component count "+moles.size()+" -> "+got.moles().length);continue;}
                double total=0;for(var value:moles)total+=Math.abs(value.getAsDouble());
                for(int c=0;c<moles.size()&&failures.size()<40;c++)
                    check(failures,at,"moles["+c+"]",moles.get(c).getAsDouble(),got.moles()[c],tolerance,Quantity.STATE,1e-9*total);
            }
            var flows=expected.getAsJsonArray("flows");
            if(flows.size()!=state.flows().length){failures.add(where+": pipe count "+flows.size()+" -> "+state.flows().length);continue;}
            for(int e=0;e<flows.size()&&failures.size()<40;e++)
                check(failures,where+" pipe "+e,"averageMassFlow",flows.get(e).getAsDouble(),state.flows()[e],tolerance,Quantity.FLOW,0);
        }
        return failures;
    }
    private enum Quantity {STATE,TEMPERATURE,FLOW}
    private static void check(List<String> failures,String at,String field,double expected,double actual,Tolerance tolerance,Quantity quantity,double floor) {
        if(Double.doubleToLongBits(expected)==Double.doubleToLongBits(actual))return;
        if(tolerance instanceof Relative relative) {
            double difference=Math.abs(expected-actual),allowed=switch(quantity) {
                case TEMPERATURE->relative.temperature();
                case FLOW->relative.flow()*Math.max(Math.abs(expected),1e-6);
                case STATE->relative.state()*Math.max(Math.abs(expected),floor);
            };
            if(difference<=allowed)return;
            failures.add(at+" "+field+": expected "+expected+", got "+actual+" (difference "+difference+" > "+allowed+")");
            return;
        }
        failures.add(at+" "+field+": expected "+expected+", got "+actual+" (difference "+Math.abs(expected-actual)+")");
    }
    private static void fraction(List<String> failures,String at,String field,double expected,double actual,double expectedVolume,double actualVolume,Tolerance tolerance) {
        if(Double.doubleToLongBits(expected)==Double.doubleToLongBits(actual))return;
        if(tolerance instanceof Relative relative) {
            double difference=Math.abs(expected/expectedVolume-actual/actualVolume);
            if(difference<=relative.phaseFraction())return;
            failures.add(at+" "+field+" fraction: expected "+expected/expectedVolume+", got "+actual/actualVolume+" (difference "+difference+" > "+relative.phaseFraction()+")");
            return;
        }
        failures.add(at+" "+field+": expected "+expected+", got "+actual+" (difference "+Math.abs(expected-actual)+")");
    }
}

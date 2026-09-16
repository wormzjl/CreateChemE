package com.wormzjl.createcheme.fluid.benchmark;

import com.google.gson.*;
import com.wormzjl.createcheme.fluid.benchmark.SolverRegressionHarness.*;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Cost and trajectory regression for the solver optimization work packages. Excluded from the
 * ordinary {@code test} task (all of {@code fluid/benchmark} is); run it with
 * {@code gradlew fluidSolverRegression}. Fixtures that need the stress-world snapshot are skipped
 * when it is absent, so a checkout without {@code build/probe} stays green.
 */
class FluidSolverRegressionTest {
    /** Relative gate used once a work package legitimately changes the accepted step sequence,
     * exactly as specified for A1: 1e-6 relative on pressure/mass, 1e-4 K, 1e-6 on phase volume
     * fractions, 1e-3 on average flows relative to max(|q|,1e-6), with no absolute flow escape. */
    private static final Tolerance A1_TOLERANCE=new Relative(1e-6,1e-4,1e-6,1e-3,0);
    /**
     * Declared gate since A2 (retained per-island solver). The retained factorization serves as the
     * modified-Newton preconditioner for the first solve of the next interval, so that solve takes
     * a different path to the same root: both end states satisfy the same 1e-9 equation gate and
     * the same conservation audit, and they differ by at most that tolerance. The step sequence and
     * the substep counts are unchanged, and the cold and chain fixtures - which retain nothing,
     * having one interval - stay bitwise identical.
     *
     * <p>Measured against the 154007d references: 1.0e-9 relative on state and moles (the worst
     * point is a fixed 1 m^3 reservoir volume, which the baseline closed to 1+1e-9 and the retained
     * solve closes to 1+4e-16, so A2 is the more accurate of the two there), 1.3e-9 K, 5.5e-12 on
     * phase fractions, and 2.4e-9 kg/s on a pipe carrying 7.5e-7 kg/s - 3% of the interval
     * controller's own declared flow floor of about 7.7e-8 kg/s for that pipe. The harness prints
     * the measured maximum and its location every run, so any growth is visible immediately.
     */
    private static final Tolerance WITHIN_NEWTON_TOLERANCE=new Relative(1e-8,1e-7,1e-9,1e-6,5e-8);

    @Test void replaySavedIslandsAgainstCapturedReferences() {
        var model=SolverRegressionHarness.model();
        var skipped=new ArrayList<String>();
        var fixtures=fixtures(model,skipped);
        skipped.forEach(reason->System.out.println("Fluid solver regression SKIP "+reason));
        Assumptions.assumeFalse(fixtures.isEmpty(),"No solver regression fixture is available: "+skipped);
        boolean capture=Boolean.getBoolean("fluid.regression.capture");
        var report=new JsonObject();var measured=new JsonArray();report.add("fixtures",measured);
        report.addProperty("capture",capture);report.addProperty("mode",System.getProperty("fluid.regression.mode","declared"));
        var failures=new ArrayList<String>();
        for(var fixture:fixtures) {
            var intervals=SolverRegressionHarness.replay(model,fixture);
            print(fixture,intervals);measured.add(measurements(fixture,intervals));
            var path=SolverRegressionHarness.reference(fixture.name());
            if(capture){SolverRegressionHarness.write(path,SolverRegressionHarness.encode(fixture.name(),intervals));
                System.out.println("Fluid solver regression CAPTURED "+path);continue;}
            if(!Files.isReadable(path)){failures.add(fixture.name()+": missing reference "+path+" (capture with -Dfluid.regression.capture=true)");continue;}
            var comparison=SolverRegressionHarness.compare(fixture.name(),SolverRegressionHarness.read(path),intervals,tolerance(fixture));
            System.out.println(fixture.name()+" vs reference: "+comparison.deviations());
            failures.addAll(comparison.failures());
        }
        SolverRegressionHarness.write(SolverRegressionHarness.REPORT,report);
        System.out.println("Fluid solver regression report "+SolverRegressionHarness.REPORT.toAbsolutePath());
        assertTrue(failures.isEmpty(),failures.size()+" regression differences:\n"+String.join("\n",failures.subList(0,Math.min(20,failures.size()))));
    }

    /** {@code -Dfluid.regression.mode=exact|relative} overrides the fixture's declared gate. */
    private static Tolerance tolerance(Fixture fixture) {
        return switch(System.getProperty("fluid.regression.mode","declared")) {
            case "exact"->new Exact();
            case "relative"->A1_TOLERANCE;
            default->fixture.tolerance();
        };
    }

    private static List<Fixture> fixtures(FluidThermodynamics model,List<String> skipped) {
        var fixtures=new ArrayList<Fixture>();
        island(model,"core.dat",11312,"quiet-11312","quiescent 30-reservoir / 45-pipe island",2,5).ifPresentOrElse(fixtures::add,
                ()->skipped.add("quiet-11312 (no readable core.dat island 11312)"));
        island(model,"core.dat",11324,"quiet-11324","quiescent 18-reservoir / 27-pipe island",2,5).ifPresentOrElse(fixtures::add,
                ()->skipped.add("quiet-11324 (no readable core.dat island 11324)"));
        island(model,"core-fallback.dat",11312,"cold-11312","unadvanced 30-reservoir island, the transient case",0,1).ifPresentOrElse(fixtures::add,
                ()->skipped.add("cold-11312 (no readable core-fallback.dat island 11312)"));
        fixtures.add(new Fixture("chain-100","100-reservoir cosine-pressure chain",
                SolverRegressionHarness.cosineChain(model,100),0,1,WITHIN_NEWTON_TOLERANCE));
        return List.copyOf(fixtures);
    }
    private static Optional<Fixture> island(FluidThermodynamics model,String file,long id,String name,String description,int warmup,int intervals) {
        var snapshot=SolverRegressionHarness.snapshot(file);
        if(snapshot.isEmpty())return Optional.empty();
        PassiveNetwork graph=SolverRegressionHarness.islands(model,snapshot.get()).get(id);
        return graph==null?Optional.empty():Optional.of(new Fixture(name,description,graph,warmup,intervals,WITHIN_NEWTON_TOLERANCE));
    }

    private static void print(Fixture fixture,List<IntervalReport> intervals) {
        System.out.println("\n### "+fixture.name()+" - "+fixture.description()+" ("+fixture.intervals()+" x 5 s)");
        var names=SolverDiagnostics.names();
        System.out.println("| interval | wall ms | allocated MB | accepted | rejected | "+String.join(" | ",names)+" |");
        for(var interval:intervals) {
            var cells=new ArrayList<String>();
            cells.add(String.valueOf(interval.index()));
            cells.add(String.format(Locale.ROOT,"%.2f",interval.wallMilliseconds()));
            cells.add(String.format(Locale.ROOT,"%.1f",interval.allocatedBytes()/1048576.0));
            cells.add(String.valueOf(interval.state().accepted()));cells.add(String.valueOf(interval.state().rejected()));
            for(String name:names)cells.add(String.valueOf(interval.diagnostics().value(name)));
            System.out.println("| "+String.join(" | ",cells)+" |");
        }
        System.out.println("attempts: "+intervals.get(0).diagnostics().attempts());
    }
    private static JsonObject measurements(Fixture fixture,List<IntervalReport> intervals) {
        var entry=new JsonObject();entry.addProperty("fixture",fixture.name());entry.addProperty("description",fixture.description());
        var rows=new JsonArray();
        for(var interval:intervals) {
            var row=new JsonObject();row.addProperty("interval",interval.index());
            row.addProperty("wallMilliseconds",interval.wallMilliseconds());
            row.addProperty("allocatedMegabytes",interval.allocatedBytes()/1048576.0);
            row.addProperty("accepted",interval.state().accepted());row.addProperty("rejected",interval.state().rejected());
            interval.diagnostics().counters().forEach(row::addProperty);
            rows.add(row);
        }
        entry.add("intervals",rows);return entry;
    }
}

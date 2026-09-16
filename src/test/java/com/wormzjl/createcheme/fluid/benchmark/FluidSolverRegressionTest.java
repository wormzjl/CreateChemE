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
    /**
     * The gate every fixture declares against the 154007d references. Two landed work packages move
     * the trajectory without moving the physics:
     *
     * <ul>
     * <li>A2 retains the previous interval's factorization as the modified-Newton preconditioner, so
     *     the first Newton solve of an interval reaches the same root along a different path. Both
     *     end states satisfy the same 1e-9 equation gate and the same conservation audit.</li>
     * <li>A1 carries the accepted step size across intervals, so a quiet island integrates one 5 s
     *     step where it used to take 1, 2 and 2 s. Every accepted step still meets the unchanged
     *     error criteria.</li>
     * </ul>
     *
     * <p>The numbers are the ones specified for A1: 1e-6 relative on pressure, mass, volume and
     * component totals, 1e-4 K, 1e-6 on phase volume fractions, and 1e-3 on average flows - the
     * last measured against the interval controller's own per-pipe allowance, see {@link Relative}.
     * Substep counts are recorded and reported but not gated, because A1 changes them by design.
     * The measured maxima are printed on every run, so drift is visible immediately.
     */
    private static final Tolerance DECLARED=new Relative(1e-6,1e-4,1e-6,1e-3);

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
            var comparison=SolverRegressionHarness.compare(fixture.name(),SolverRegressionHarness.read(path),intervals,tolerance(fixture),
                    fixture.start().pipes(),SolverRegressionHarness.INTERVAL_SECONDS);
            System.out.println(fixture.name()+" vs reference: "+comparison.deviations());
            failures.addAll(comparison.failures());
        }
        SolverRegressionHarness.write(SolverRegressionHarness.REPORT,report);
        System.out.println("Fluid solver regression report "+SolverRegressionHarness.REPORT.toAbsolutePath());
        assertTrue(failures.isEmpty(),failures.size()+" regression differences:\n"+String.join("\n",failures.subList(0,Math.min(20,failures.size()))));
    }

    /** {@code -Dfluid.regression.mode=exact|relative} overrides the fixture's declared gate;
     * {@code exact} is how a trajectory-identical work package proves itself against a scratch
     * capture of the commit before it. */
    private static Tolerance tolerance(Fixture fixture) {
        return switch(System.getProperty("fluid.regression.mode","declared")) {
            case "exact"->new Exact();
            case "relative"->DECLARED;
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
                SolverRegressionHarness.cosineChain(model,100),0,1,DECLARED));
        return List.copyOf(fixtures);
    }
    private static Optional<Fixture> island(FluidThermodynamics model,String file,long id,String name,String description,int warmup,int intervals) {
        var snapshot=SolverRegressionHarness.snapshot(file);
        if(snapshot.isEmpty())return Optional.empty();
        PassiveNetwork graph=SolverRegressionHarness.islands(model,snapshot.get()).get(id);
        return graph==null?Optional.empty():Optional.of(new Fixture(name,description,graph,warmup,intervals,DECLARED));
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

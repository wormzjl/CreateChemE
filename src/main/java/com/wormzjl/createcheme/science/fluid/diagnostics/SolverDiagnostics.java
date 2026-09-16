package com.wormzjl.createcheme.science.fluid.diagnostics;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in instrumentation of the fluid solver. {@link #ENABLED} defaults to {@code false}; every
 * recording site then performs one volatile boolean read, allocates nothing and reads no clock, so
 * production numerics, tolerances and physics gates are unchanged whether or not it is enabled.
 *
 * <p>The counters are process-wide and additive. The nesting markers ({@link #inJacobian},
 * {@link #inReconstruct}) and the attempt log are <b>thread-confined by contract</b>: the facility
 * is meant for a single-threaded replay (the benchmark/regression harness), never for the
 * production worker pool, where several workers would interleave their markers and totals.
 */
public final class SolverDiagnostics {
    private SolverDiagnostics() {}

    /** Master switch. Off in production; only the diagnostic harness turns it on. */
    public static volatile boolean ENABLED=false;

    // ---- interval and step control ----
    public static final LongAdder implicitSolves=new LongAdder();
    public static final LongAdder activeSetPasses=new LongAdder();
    // ---- Newton ----
    public static final LongAdder newtonSolves=new LongAdder();
    public static final LongAdder newtonIterations=new LongAdder();
    public static final LongAdder newtonBacktracks=new LongAdder();
    public static final LongAdder residualEvaluations=new LongAdder();
    public static final LongAdder residualEvaluationsInJacobian=new LongAdder();
    public static final LongAdder jacobianBuilds=new LongAdder();
    public static final LongAdder jacobianColors=new LongAdder();
    public static final LongAdder jacobianNonzeros=new LongAdder();
    // ---- sparse linear algebra, Newton side ----
    public static final LongAdder luFactorizations=new LongAdder();
    public static final LongAdder luFactorNanos=new LongAdder();
    public static final LongAdder luSolves=new LongAdder();
    public static final LongAdder luSolveNanos=new LongAdder();
    public static final LongAdder luChecks=new LongAdder();
    public static final LongAdder luRefinements=new LongAdder();
    public static final LongAdder luOrderings=new LongAdder();
    public static final LongAdder luOrderingNanos=new LongAdder();
    // ---- sparse linear algebra, conservative transport side ----
    public static final LongAdder transportFactorizations=new LongAdder();
    public static final LongAdder transportFactorNanos=new LongAdder();
    public static final LongAdder transportSolves=new LongAdder();
    public static final LongAdder transportSolveNanos=new LongAdder();
    public static final LongAdder transportOrderings=new LongAdder();
    public static final LongAdder transportOrderingNanos=new LongAdder();
    // ---- properties ----
    public static final LongAdder stateCalls=new LongAdder();
    public static final LongAdder temperatureTermsCalls=new LongAdder();
    public static final LongAdder flashCalls=new LongAdder();
    // ---- reconstruction ----
    public static final LongAdder reconstructCalls=new LongAdder();
    public static final LongAdder reconstructNanos=new LongAdder();

    /** Thread-confined nesting markers; see the class contract. */
    public static boolean inJacobian=false,inReconstruct=false;

    /** One adaptive interval attempt: its step, whether it was accepted, and which term dominated. */
    public record Attempt(int index,double step,boolean accepted,String dominant,double error) {}
    private static final List<Attempt> ATTEMPTS=Collections.synchronizedList(new ArrayList<>());

    public static long begin(){return ENABLED?System.nanoTime():0;}
    public static void end(LongAdder target,long started){if(ENABLED)target.add(System.nanoTime()-started);}
    public static void count(LongAdder target){if(ENABLED)target.increment();}
    public static void count(LongAdder target,long amount){if(ENABLED)target.add(amount);}
    public static void attempt(int index,double step,boolean accepted,String dominant,double error) {
        if(ENABLED)ATTEMPTS.add(new Attempt(index,step,accepted,dominant,error));
    }
    public static List<Attempt> attempts(){synchronized(ATTEMPTS){return List.copyOf(ATTEMPTS);}}

    private static final Map<String,LongAdder> COUNTERS=counters();
    private static Map<String,LongAdder> counters() {
        var map=new LinkedHashMap<String,LongAdder>();
        map.put("implicitSolves",implicitSolves);map.put("activeSetPasses",activeSetPasses);
        map.put("newtonSolves",newtonSolves);map.put("newtonIterations",newtonIterations);map.put("newtonBacktracks",newtonBacktracks);
        map.put("residualEvaluations",residualEvaluations);map.put("residualEvaluationsInJacobian",residualEvaluationsInJacobian);
        map.put("jacobianBuilds",jacobianBuilds);map.put("jacobianColors",jacobianColors);map.put("jacobianNonzeros",jacobianNonzeros);
        map.put("luFactorizations",luFactorizations);map.put("luFactorNanos",luFactorNanos);
        map.put("luSolves",luSolves);map.put("luSolveNanos",luSolveNanos);
        map.put("luChecks",luChecks);map.put("luRefinements",luRefinements);
        map.put("luOrderings",luOrderings);map.put("luOrderingNanos",luOrderingNanos);
        map.put("transportFactorizations",transportFactorizations);map.put("transportFactorNanos",transportFactorNanos);
        map.put("transportSolves",transportSolves);map.put("transportSolveNanos",transportSolveNanos);
        map.put("transportOrderings",transportOrderings);map.put("transportOrderingNanos",transportOrderingNanos);
        map.put("stateCalls",stateCalls);map.put("temperatureTermsCalls",temperatureTermsCalls);map.put("flashCalls",flashCalls);
        map.put("reconstructCalls",reconstructCalls);map.put("reconstructNanos",reconstructNanos);
        return Collections.unmodifiableMap(map);
    }

    /** Immutable readout of every counter, in declaration order. */
    public record Sample(Map<String,Long> counters,List<Attempt> attempts) {
        public Sample {counters=Map.copyOf(counters);attempts=List.copyOf(attempts);}
        public long value(String name) {
            var found=counters.get(name);
            if(found==null)throw new IllegalArgumentException("Unknown solver diagnostic "+name);
            return found;
        }
    }
    /** Ordered counter names, for tabular reports. */
    public static List<String> names(){return List.copyOf(COUNTERS.keySet());}
    public static Sample sample() {
        var values=new LinkedHashMap<String,Long>();
        COUNTERS.forEach((name,adder)->values.put(name,adder.sum()));
        return new Sample(values,attempts());
    }
    public static void reset() {
        COUNTERS.values().forEach(LongAdder::reset);
        ATTEMPTS.clear();inJacobian=false;inReconstruct=false;
    }
}

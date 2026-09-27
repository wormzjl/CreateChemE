import java.util.ArrayList;
import java.util.List;

/**
 * JDK determinism sweep: evaluates every java.lang.Math function the CreateChemE science code calls, and its
 * StrictMath counterpart, over a deterministic sample of inputs covering the ranges the thermodynamics uses, and
 * prints per function the number of inputs where Math != StrictMath (bitwise), the maximum ulp difference, and hashes
 * of all Math and all StrictMath outputs. Run the same class under many runtimes and compare the hashes.
 *
 * <p>The inputs are built from a SplitMix64 generator with only exact bit operations and correctly rounded + - * /,
 * so the sample itself is bitwise identical on every runtime (the "inputs" hash per function proves it).
 *
 * <p>Usage: {@code java MathSweep [sweep|bench] [reps]}. sweep (default) evaluates the whole sample {@code reps}
 * times (default 3, so C2/JIT intrinsics engage) and reports the last repetition, plus whether every repetition gave
 * the same Math hash. bench prints indicative ns/call of Math vs StrictMath for log, exp, pow (warmed loop with a
 * black hole; not JMH). tier evaluates each function twice in one JVM, cold (first pass, interpreter/C1/OSR) and after
 * five warm passes, and prints every input whose Math result changed between the two (tier-dependent results).
 * {@code dump <fn> <reps>} prints every input and Math output of one function in hex (diff a JIT run with an -Xint run).
 */
public final class MathSweep {
    private static final int PER_FUNCTION = 1 << 20; // 1,048,576 inputs per function

    private MathSweep() {
    }

    // --- deterministic input generation (exact operations only) ---------------------------------------------------

    private static long state;

    private static long next() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform in [0, 1): 53 random bits times 2^-53 (exact). */
    private static double unit() {
        return (next() >>> 11) * 0x1p-53;
    }

    /** Uniform in [lo, hi] (correctly rounded * and +). */
    private static double linear(double lo, double hi) {
        return lo + (hi - lo) * unit();
    }

    /** Log-uniform over binades: a random mantissa in a random binade between those of lo and hi (exact bits). */
    private static double logUniform(double lo, double hi) {
        int eLo = Math.getExponent(lo), eHi = Math.getExponent(hi);
        while (true) {
            int e = eLo + (int) Long.remainderUnsigned(next(), eHi - eLo + 1);
            long bits = ((long) (e + 1023) << 52) | (next() >>> 12);
            double x = Double.longBitsToDouble(bits);
            if (x >= lo && x <= hi) {
                return x;
            }
        }
    }

    private static double signed(double x) {
        return (next() & 1L) == 0 ? x : -x;
    }

    private interface Gen {
        double get();
    }

    /** Fills n values from a list of weighted generators, round-robin by weight. */
    private static double[] sample(long seed, int n, Object... weightedGens) {
        state = seed;
        List<Gen> gens = new ArrayList<>();
        for (int i = 0; i < weightedGens.length; i += 2) {
            int w = (Integer) weightedGens[i];
            for (int k = 0; k < w; k++) {
                gens.add((Gen) weightedGens[i + 1]);
            }
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = gens.get(i % gens.size()).get();
        }
        return out;
    }

    // --- the functions ----------------------------------------------------------------------------------------------

    private enum Fn {
        LOG, EXP, POW, CBRT, LOG1P, EXPM1, COS, LOG10, ACOS, SQRT, FMA
    }

    private static final class Case {
        final Fn fn;
        final double[] a;
        final double[] b;
        final double[] c;

        Case(Fn fn, double[] a, double[] b, double[] c) {
            this.fn = fn;
            this.a = a;
            this.b = b;
            this.c = c;
        }
    }

    private static List<Case> cases() {
        int n = PER_FUNCTION;
        List<Case> list = new ArrayList<>();
        // log: pressures, temperatures, mole fractions, reduced quantities, Z and near-1 arguments, all binades
        list.add(new Case(Fn.LOG, sample(1, n,
                3, (Gen) () -> logUniform(1e2, 1e7),
                2, (Gen) () -> linear(200, 1000),
                3, (Gen) () -> logUniform(1e-14, 1.0),
                2, (Gen) () -> linear(0.05, 10),
                2, (Gen) () -> linear(1e-3, 2),
                2, (Gen) () -> 1.0 + linear(-1e-3, 1e-3),
                1, (Gen) () -> logUniform(Double.MIN_NORMAL, Double.MAX_VALUE)), null, null));
        // exp: fugacity/Boltzmann arguments, wide range, small arguments
        list.add(new Case(Fn.EXP, sample(2, n,
                3, (Gen) () -> linear(-50, 50),
                2, (Gen) () -> linear(-100, 0),
                1, (Gen) () -> linear(-745, 709),
                2, (Gen) () -> signed(logUniform(1e-14, 1e-1)),
                2, (Gen) () -> linear(-10, 10)), null, null));
        // pow: fractional exponents on reduced/ratio bases, integer exponents, powers of ten, Watson/Rackett forms
        double[] powBase = sample(3, n,
                3, (Gen) () -> logUniform(1e-14, 1e7),
                3, (Gen) () -> linear(0.01, 1.0),
                2, (Gen) () -> linear(0.2, 5),
                2, (Gen) () -> linear(1e3, 1e7));
        double[] fixedExp = {0.5, 1.5, 2.5, 3.5, 6.5, 7.5, 2.0 / 7.0, 2.0 / 3.0, 1.0 / 3.0, 0.2, 0.25, 0.38, 0.755,
            0.842, 0.9, 0.91, 1.9, -0.5, -1.0, 2.0, 3.0, 4.0, 5.0, 0.0, 1.0};
        state = 33;
        double[] powExp = new double[n];
        for (int i = 0; i < n; i++) {
            long r = next();
            powExp[i] = (r & 3L) == 0 ? linear(-3, 3) : fixedExp[(int) Long.remainderUnsigned(r >>> 2, fixedExp.length)];
        }
        for (int i = 0; i < n; i += 97) { // pow(10, exponent) as the column's tolerance decoding does
            powBase[i] = 10.0;
            powExp[i] = linear(-16, 3);
        }
        list.add(new Case(Fn.POW, powBase, powExp, null));
        // cbrt: cubic-root terms of both signs (Cardano), wide range
        list.add(new Case(Fn.CBRT, sample(4, n,
                4, (Gen) () -> signed(logUniform(1e-20, 1e3)),
                2, (Gen) () -> signed(logUniform(1e-14, 1e7)),
                1, (Gen) () -> signed(logUniform(Double.MIN_NORMAL, Double.MAX_VALUE)),
                2, (Gen) () -> linear(-1, 1)), null, null));
        // log1p: near zero of both signs, ratios, up to 1e7
        list.add(new Case(Fn.LOG1P, sample(5, n,
                3, (Gen) () -> signed(logUniform(1e-14, 1e-1)),
                2, (Gen) () -> linear(-0.999, 1),
                2, (Gen) () -> logUniform(1e-3, 1e7),
                1, (Gen) () -> linear(-1 + 1e-12, -0.5)), null, null));
        // expm1: near zero of both signs, log K range, compressibility exponents
        list.add(new Case(Fn.EXPM1, sample(6, n,
                3, (Gen) () -> signed(logUniform(1e-14, 1e-1)),
                3, (Gen) () -> linear(-50, 50),
                1, (Gen) () -> linear(-40, 700),
                1, (Gen) () -> linear(-1, 1)), null, null));
        // cos: trigonometric cubic roots acos(.)/3 - k 2pi/3, plus a wider range
        list.add(new Case(Fn.COS, sample(7, n,
                4, (Gen) () -> linear(0, Math.PI / 3) - (double) (next() % 3 + 3) % 3 * (2.0 * Math.PI / 3.0),
                2, (Gen) () -> linear(-2 * Math.PI, 2 * Math.PI),
                1, (Gen) () -> linear(-1e3, 1e3),
                1, (Gen) () -> signed(logUniform(1e-14, 1e-1))), null, null));
        // log10: friction-factor arguments and tolerances
        list.add(new Case(Fn.LOG10, sample(8, n,
                3, (Gen) () -> logUniform(1e-14, 1.0),
                2, (Gen) () -> logUniform(1e-7, 1e-1),
                2, (Gen) () -> logUniform(1, 1e7),
                1, (Gen) () -> 1.0 + linear(-1e-6, 1e-6)), null, null));
        // acos: clamped [-1, 1], dense near the ends
        list.add(new Case(Fn.ACOS, sample(9, n,
                4, (Gen) () -> linear(-1, 1),
                2, (Gen) () -> 1.0 - logUniform(1e-16, 1e-2),
                2, (Gen) () -> -1.0 + logUniform(1e-16, 1e-2),
                1, (Gen) () -> signed(logUniform(1e-14, 1e-1))), null, null));
        // sqrt: discriminants, pressures, tiny values
        list.add(new Case(Fn.SQRT, sample(10, n,
                3, (Gen) () -> logUniform(1e-30, 1e7),
                2, (Gen) () -> linear(0, 10),
                1, (Gen) () -> logUniform(Double.MIN_NORMAL, Double.MAX_VALUE)), null, null));
        // fma: a*b+c with cancellation (c close to -a*b) and generic triples
        state = 11;
        double[] fa = new double[n], fb = new double[n], fc = new double[n];
        for (int i = 0; i < n; i++) {
            fa[i] = signed(logUniform(1e-10, 1e7));
            fb[i] = signed(logUniform(1e-10, 1e7));
            fc[i] = (i & 1) == 0 ? -(fa[i] * fb[i]) * (1.0 + linear(-1e-12, 1e-12)) : signed(logUniform(1e-10, 1e10));
        }
        list.add(new Case(Fn.FMA, fa, fb, fc));
        return list;
    }

    // One method per function and flavour so the JIT compiles each loop with its intrinsic.
    private static void mathLog(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = Math.log(a[i]); }
    private static void strictLog(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = StrictMath.log(a[i]); }
    private static void mathExp(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = Math.exp(a[i]); }
    private static void strictExp(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = StrictMath.exp(a[i]); }
    private static void mathPow(double[] a, double[] b, double[] o) { for (int i = 0; i < a.length; i++) o[i] = Math.pow(a[i], b[i]); }
    private static void strictPow(double[] a, double[] b, double[] o) { for (int i = 0; i < a.length; i++) o[i] = StrictMath.pow(a[i], b[i]); }
    private static void mathCbrt(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = Math.cbrt(a[i]); }
    private static void strictCbrt(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = StrictMath.cbrt(a[i]); }
    private static void mathLog1p(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = Math.log1p(a[i]); }
    private static void strictLog1p(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = StrictMath.log1p(a[i]); }
    private static void mathExpm1(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = Math.expm1(a[i]); }
    private static void strictExpm1(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = StrictMath.expm1(a[i]); }
    private static void mathCos(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = Math.cos(a[i]); }
    private static void strictCos(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = StrictMath.cos(a[i]); }
    private static void mathLog10(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = Math.log10(a[i]); }
    private static void strictLog10(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = StrictMath.log10(a[i]); }
    private static void mathAcos(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = Math.acos(a[i]); }
    private static void strictAcos(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = StrictMath.acos(a[i]); }
    private static void mathSqrt(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = Math.sqrt(a[i]); }
    private static void strictSqrt(double[] a, double[] o) { for (int i = 0; i < a.length; i++) o[i] = StrictMath.sqrt(a[i]); }
    private static void mathFma(double[] a, double[] b, double[] c, double[] o) { for (int i = 0; i < a.length; i++) o[i] = Math.fma(a[i], b[i], c[i]); }
    private static void strictFma(double[] a, double[] b, double[] c, double[] o) { for (int i = 0; i < a.length; i++) o[i] = StrictMath.fma(a[i], b[i], c[i]); }

    private static void eval(Case k, boolean strict, double[] o) {
        switch (k.fn) {
            case LOG -> { if (strict) strictLog(k.a, o); else mathLog(k.a, o); }
            case EXP -> { if (strict) strictExp(k.a, o); else mathExp(k.a, o); }
            case POW -> { if (strict) strictPow(k.a, k.b, o); else mathPow(k.a, k.b, o); }
            case CBRT -> { if (strict) strictCbrt(k.a, o); else mathCbrt(k.a, o); }
            case LOG1P -> { if (strict) strictLog1p(k.a, o); else mathLog1p(k.a, o); }
            case EXPM1 -> { if (strict) strictExpm1(k.a, o); else mathExpm1(k.a, o); }
            case COS -> { if (strict) strictCos(k.a, o); else mathCos(k.a, o); }
            case LOG10 -> { if (strict) strictLog10(k.a, o); else mathLog10(k.a, o); }
            case ACOS -> { if (strict) strictAcos(k.a, o); else mathAcos(k.a, o); }
            case SQRT -> { if (strict) strictSqrt(k.a, o); else mathSqrt(k.a, o); }
            case FMA -> { if (strict) strictFma(k.a, k.b, k.c, o); else mathFma(k.a, k.b, k.c, o); }
        }
    }

    // --- comparison --------------------------------------------------------------------------------------------------

    /** FNV-1a over the canonical bits (all NaNs hash alike). */
    private static long hash(double[] v) {
        long h = 0xcbf29ce484222325L;
        for (double d : v) {
            long bits = Double.doubleToLongBits(d);
            for (int s = 0; s < 64; s += 8) {
                h ^= (bits >>> s) & 0xFF;
                h *= 0x100000001b3L;
            }
        }
        return h;
    }

    private static long ordered(double d) {
        long bits = Double.doubleToLongBits(d);
        return bits < 0 ? Long.MIN_VALUE - bits : bits;
    }

    private static long ulps(double x, double y) {
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return Double.isNaN(x) && Double.isNaN(y) ? 0 : Long.MAX_VALUE;
        }
        long d = ordered(x) - ordered(y);
        return d < 0 ? -d : d;
    }

    private static void sweep(int reps) {
        List<Case> cases = cases();
        System.out.println("# java.runtime.version=" + System.getProperty("java.runtime.version")
                + " java.vm.name=" + System.getProperty("java.vm.name")
                + " java.vendor=" + System.getProperty("java.vendor")
                + " java.vendor.version=" + System.getProperty("java.vendor.version")
                + " os.arch=" + System.getProperty("os.arch"));
        System.out.println("# inputs per function " + PER_FUNCTION + ", repetitions " + reps + " (the last is reported)");
        System.out.printf("%-6s %9s %8s %-18s %-18s %-18s %-11s %s%n", "fn", "differ", "maxUlp", "mathHash",
                "strictHash", "inputHash", "repsStable", "firstDifferingInput(hex) math strict");
        for (Case k : cases) {
            int n = k.a.length;
            double[] m = new double[n], s = new double[n];
            long firstHash = 0;
            boolean stable = true;
            for (int r = 0; r < reps; r++) {
                eval(k, false, m);
                eval(k, true, s);
                long h = hash(m);
                if (r == 0) {
                    firstHash = h;
                } else if (h != firstHash) {
                    stable = false;
                }
            }
            long differ = 0, maxUlp = 0;
            String first = "-";
            for (int i = 0; i < n; i++) {
                if (Double.doubleToLongBits(m[i]) != Double.doubleToLongBits(s[i])) {
                    differ++;
                    maxUlp = Math.max(maxUlp, ulps(m[i], s[i]));
                    if (first.equals("-")) {
                        first = Double.toHexString(k.a[i]) + (k.b != null ? "," + Double.toHexString(k.b[i]) : "")
                                + (k.c != null ? "," + Double.toHexString(k.c[i]) : "")
                                + " " + Double.toHexString(m[i]) + " " + Double.toHexString(s[i]);
                    }
                }
            }
            long inHash = hash(k.a) ^ (k.b != null ? Long.rotateLeft(hash(k.b), 1) : 0)
                    ^ (k.c != null ? Long.rotateLeft(hash(k.c), 2) : 0);
            System.out.printf("%-6s %9d %8d %016x   %016x   %016x   %-11s %s%n", k.fn.name().toLowerCase(), differ, maxUlp,
                    hash(m), hash(s), inHash, stable ? "yes" : "NO", first);
        }
    }

    /** Prints input and Math output (hex) of one function after {@code reps} evaluations, for diffing two runs. */
    private static void dump(String fn, int reps) {
        for (Case k : cases()) {
            if (!k.fn.name().equalsIgnoreCase(fn)) {
                continue;
            }
            double[] o = new double[k.a.length];
            for (int r = 0; r < reps; r++) {
                eval(k, false, o);
            }
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < o.length; i++) {
                b.append(Double.toHexString(k.a[i])).append(k.b != null ? "," + Double.toHexString(k.b[i]) : "")
                        .append(' ').append(Double.toHexString(o[i])).append('\n');
            }
            System.out.print(b);
        }
    }

    private static void tier() {
        System.out.println("# tier check on " + System.getProperty("java.runtime.version") + " " + System.getProperty("java.vm.name")
                + " " + System.getProperty("java.vendor.version"));
        for (Case k : cases()) {
            int n = k.a.length;
            double[] cold = new double[n], hot = new double[n];
            eval(k, false, cold);
            for (int r = 0; r < 5; r++) {
                eval(k, false, hot);
            }
            int changed = 0;
            for (int i = 0; i < n; i++) {
                if (Double.doubleToLongBits(cold[i]) != Double.doubleToLongBits(hot[i])) {
                    changed++;
                    if (changed <= 5) {
                        double strict = switch (k.fn) {
                            case POW -> StrictMath.pow(k.a[i], k.b[i]);
                            case FMA -> StrictMath.fma(k.a[i], k.b[i], k.c[i]);
                            default -> Double.NaN;
                        };
                        System.out.println("  " + k.fn.name().toLowerCase() + " input " + Double.toHexString(k.a[i])
                                + (k.b != null ? "," + Double.toHexString(k.b[i]) : "") + " cold " + Double.toHexString(cold[i])
                                + " hot " + Double.toHexString(hot[i]) + (Double.isNaN(strict) ? "" : " strict " + Double.toHexString(strict)));
                    }
                }
            }
            System.out.printf("%-6s cold-vs-hot differing inputs %d%n", k.fn.name().toLowerCase(), changed);
        }
    }

    // --- indicative cost ---------------------------------------------------------------------------------------------

    private static volatile double sink;

    private static void bench() {
        int n = 1 << 16;
        state = 99;
        double[] x = new double[n], y = new double[n], z = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = logUniform(1e-6, 1e6);
            y[i] = linear(-30, 30);
            z[i] = linear(-3, 3);
        }
        String[] names = {"log", "exp", "pow", "cbrt", "expm1", "log1p"};
        System.out.println("# indicative ns/call (warmed loop, volatile black hole, not JMH) on "
                + System.getProperty("java.runtime.version") + " " + System.getProperty("java.vm.name"));
        for (String name : names) {
            double[] best = {Double.MAX_VALUE, Double.MAX_VALUE};
            for (int round = 0; round < 30; round++) {
                for (int flavour = 0; flavour < 2; flavour++) {
                    boolean strict = flavour == 1;
                    long t0 = System.nanoTime();
                    double acc = 0;
                    for (int rep = 0; rep < 16; rep++) {
                        for (int i = 0; i < n; i++) {
                            acc += call(name, strict, x[i], y[i], z[i]);
                        }
                    }
                    long t1 = System.nanoTime();
                    sink = acc;
                    best[flavour] = Math.min(best[flavour], (t1 - t0) / (16.0 * n));
                }
            }
            System.out.printf("%-6s Math %6.2f ns  StrictMath %6.2f ns  ratio %.2f%n", name, best[0], best[1], best[1] / best[0]);
        }
    }

    private static double call(String name, boolean strict, double x, double y, double z) {
        return switch (name) {
            case "log" -> strict ? StrictMath.log(x) : Math.log(x);
            case "exp" -> strict ? StrictMath.exp(y) : Math.exp(y);
            case "pow" -> strict ? StrictMath.pow(x, z) : Math.pow(x, z);
            case "cbrt" -> strict ? StrictMath.cbrt(y) : Math.cbrt(y);
            case "expm1" -> strict ? StrictMath.expm1(y) : Math.expm1(y);
            default -> strict ? StrictMath.log1p(x) : Math.log1p(x);
        };
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "sweep";
        int reps = args.length > 1 && !mode.equals("dump") ? Integer.parseInt(args[1]) : 3;
        if (mode.equals("bench")) {
            bench();
        } else if (mode.equals("tier")) {
            tier();
        } else if (mode.equals("dump")) {
            dump(args[1], Integer.parseInt(args[2]));
        } else {
            sweep(reps);
        }
    }
}

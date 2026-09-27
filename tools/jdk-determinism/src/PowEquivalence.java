import com.wormzjl.createcheme.science.math.DeterministicMath;

/**
 * Bitwise comparison of DeterministicMath.pow (the allocation-free fdlibm e_pow.c transliteration) with StrictMath.pow
 * over N (default 50 million) inputs per class: uniformly random bit patterns (every double, NaN, infinities,
 * subnormals), negative bases with integer and half-integer exponents, bases near 1 with huge exponents, results near
 * overflow and in the subnormal range, integer exponents, and the thermodynamic ranges. Run it under several runtimes
 * and with -Xint: any difference prints the input and both results and the exit code is 1.
 * Usage: java -cp <main classes>:. PowEquivalence [N]   |   PowEquivalence bench  (indicative ns/call of Math.pow,
 * StrictMath.pow and DeterministicMath.pow: warmed loop, volatile black hole, not JMH)
 */
public class PowEquivalence {
    private static long state = 0x2026_0927L;

    private static long next() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit() {
        return (next() >>> 11) * 0x1p-53;
    }

    private static double bits() {
        return Double.longBitsToDouble(next());
    }

    private static double logUniform(int eLo, int eHi) {
        int e = eLo + (int) Long.remainderUnsigned(next(), eHi - eLo + 1);
        return Double.longBitsToDouble(((long) (e + 1023) << 52) | (next() >>> 12));
    }

    private static volatile double sink;

    private static void bench() {
        int m = 1 << 16;
        double[] x = new double[m], y = new double[m];
        for (int i = 0; i < m; i++) {
            x[i] = logUniform(-20, 20);
            y[i] = (unit() - 0.5) * 6;
        }
        double[] best = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        for (int round = 0; round < 30; round++) {
            for (int f = 0; f < 3; f++) {
                long t0 = System.nanoTime();
                double acc = 0;
                for (int rep = 0; rep < 16; rep++) {
                    for (int i = 0; i < m; i++) {
                        acc += f == 0 ? Math.pow(x[i], y[i]) : f == 1 ? StrictMath.pow(x[i], y[i]) : DeterministicMath.pow(x[i], y[i]);
                    }
                }
                sink = acc;
                best[f] = Math.min(best[f], (System.nanoTime() - t0) / (16.0 * m));
            }
        }
        System.out.printf("%s %s: pow ns/call Math %.2f  StrictMath %.2f  DeterministicMath %.2f%n",
                System.getProperty("java.runtime.version"), System.getProperty("java.vm.name"), best[0], best[1], best[2]);
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("bench")) {
            bench();
            return;
        }
        long n = args.length > 0 ? Long.parseLong(args[0]) : 50_000_000L;
        String[] names = {"random bits", "negative base, integer or half exponent", "base near 1, huge exponent",
            "near overflow/underflow and subnormal results", "small integer exponents", "thermodynamic ranges"};
        long total = 0, differ = 0;
        for (int c = 0; c < names.length; c++) {
            long d = 0, nan = 0, inf = 0, zero = 0, sub = 0;
            for (long i = 0; i < n; i++) {
                double x, y;
                switch (c) {
                    case 0 -> { x = bits(); y = bits(); }
                    case 1 -> { x = -logUniform(-30, 30); y = (double) ((long) (next() % 200)) * ((next() & 1) == 0 ? 1.0 : 0.5); }
                    case 2 -> { x = 1.0 + (unit() - 0.5) * 0x1p-18; y = ((next() & 1) == 0 ? 1 : -1) * logUniform(28, 70); }
                    case 3 -> {
                        x = logUniform(-60, 60);
                        double log2x = Math.getExponent(x) + (x / Math.scalb(1.0, Math.getExponent(x)) - 1.0);
                        double target = (next() & 1) == 0 ? 1023.0 + unit() * 2 : -1022.0 - unit() * 55;
                        y = log2x == 0 ? target : target / log2x;
                    }
                    case 4 -> { x = ((next() & 1) == 0 ? 1 : -1) * logUniform(-100, 100); y = (double) ((long) (next() % 40)); }
                    default -> { x = logUniform(-47, 24); y = (unit() - 0.5) * 8; }
                }
                double a = DeterministicMath.pow(x, y), b = StrictMath.pow(x, y);
                if (Double.isNaN(b)) nan++;
                else if (Double.isInfinite(b)) inf++;
                else if (b == 0) zero++;
                else if (Math.abs(b) < Double.MIN_NORMAL) sub++;
                if (Double.doubleToRawLongBits(a) != Double.doubleToRawLongBits(b)
                        && !(Double.isNaN(a) && Double.isNaN(b))) {
                    if (d < 10) {
                        System.out.println("DIFF " + names[c] + ": pow(" + Double.toHexString(x) + ", " + Double.toHexString(y)
                                + ") DeterministicMath " + Double.toHexString(a) + " StrictMath " + Double.toHexString(b));
                    }
                    d++;
                }
            }
            System.out.printf("%-48s %d inputs, %d differ   (results: NaN %d, infinite %d, zero %d, subnormal %d)%n", names[c], n, d, nan, inf, zero, sub);
            total += n;
            differ += d;
        }
        System.out.println(System.getProperty("java.runtime.version") + " " + System.getProperty("java.vm.name")
                + ": " + total + " inputs, " + differ + " differ (NaN payloads compared as NaN)");
        System.exit(differ == 0 ? 0 : 1);
    }
}

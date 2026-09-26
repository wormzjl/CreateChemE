import com.wormzjl.createcheme.science.thermo.*;
import java.util.*;

public class Scratch {
    static PengRobinsonKernel kernel(double[] tc, double[] pc, double[] w) {
        return new PengRobinsonKernel(tc, pc, w, new double[tc.length][tc.length],
            Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE);
    }
    // brute force binary: min over w1 of D(w) with both roots
    static double brute(PengRobinsonKernel k, double T, double P, double[] z, double[] d) {
        var ws = k.newWorkspace(); var e = k.newEvaluation();
        double best = Double.POSITIVE_INFINITY;
        java.util.function.DoubleUnaryOperator D = x -> {
            double[] c = {x, 1 - x}; double m = Double.POSITIVE_INFINITY;
            for (var r : PengRobinsonKernel.Root.values()) {
                k.evaluate(T, P, c, r, ws, e);
                double[] lp = e.logFugacityCoefficientsView();
                double s = 0; for (int i = 0; i < 2; i++) s += c[i] * (Math.log(c[i]) + lp[i] - d[i]);
                m = Math.min(m, s);
            }
            return m;
        };
        int N = Integer.getInteger("grid", 4000); List<Double> xs = new ArrayList<>();
        for (int i = -10 * 10; i < -20; i++) { double v = Math.pow(10, i / 10.0); xs.add(v); xs.add(1 - v); }
        for (int i = 1; i < N; i++) xs.add(i / (double) N);
        Collections.sort(xs);
        double[] x = xs.stream().mapToDouble(Double::doubleValue).filter(v -> v > 0 && v < 1).toArray();
        double[] f = new double[x.length];
        for (int i = 0; i < x.length; i++) f[i] = D.applyAsDouble(x[i]);
        for (int i = 0; i < x.length; i++) {
            best = Math.min(best, f[i]);
            if (i > 0 && i < x.length - 1 && f[i] <= f[i - 1] && f[i] <= f[i + 1]) {
                double a = x[i - 1], b = x[i + 1]; double g = (Math.sqrt(5) - 1) / 2;
                double c1 = b - g * (b - a), c2 = a + g * (b - a); double f1 = D.applyAsDouble(c1), f2 = D.applyAsDouble(c2);
                for (int it = 0; it < 80; it++) {
                    if (f1 < f2) { b = c2; c2 = c1; f2 = f1; c1 = b - g * (b - a); f1 = D.applyAsDouble(c1); }
                    else { a = c1; c1 = c2; f1 = f2; c2 = a + g * (b - a); f2 = D.applyAsDouble(c2); }
                }
                best = Math.min(best, Math.min(f1, f2));
            }
        }
        return best;
    }
    public static void main(String[] args) {
        var k = kernel(new double[]{190.564, 126.192}, new double[]{4599200.0, 3395800}, new double[]{0.01142, 0.0372});
        var s = new TangentPlaneStability(k);
        var ws = k.newWorkspace(); var ev = k.newEvaluation(); var el = k.newEvaluation();
        int agree = 0, total = 0;
        for (double T : new double[]{110, 120}) for (double P : new double[]{0.5e6, 1e6, 1.5e6, 2e6, 2.5e6, 3e6})
            for (double zn : new double[]{0.1, 0.3, 0.5, 0.7, 0.9}) {
                double[] z = {1 - zn, zn};
                var r = s.test(T, P, z);
                // reference d on min-G root
                k.evaluate(T, P, z, PengRobinsonKernel.Root.VAPOR, ws, ev); k.evaluate(T, P, z, PengRobinsonKernel.Root.LIQUID, ws, el);
                double gv = 0, gl = 0; for (int i = 0; i < 2; i++) { gv += z[i] * ev.logFugacityCoefficient(i); gl += z[i] * el.logFugacityCoefficient(i); }
                var fe = gl < gv ? el : ev;
                double[] d = new double[2]; for (int i = 0; i < 2; i++) d[i] = Math.log(z[i]) + fe.logFugacityCoefficient(i);
                double b = brute(k, T, P, z, d);
                boolean bu = b < -1e-8; boolean su = r.verdict() == TangentPlaneStability.Verdict.UNSTABLE;
                total++; if (bu == su) agree++;
                System.out.printf("T=%5.1f P=%4.1fMPa zN2=%.1f  %-10s tm=%+.3e  brute D=%+.3e (tm=%+.3e) feed=%s trial=%s x=%.4f trials=%d it=%d ev=%d dv=%d %s%n",
                    T, P / 1e6, zn, r.verdict(), r.minimumTangentPlaneDistance(), b, -Math.expm1(-b), r.feedRoot(), r.trialRoot(), r.trialComposition()[1], r.trials(), r.iterations(), r.kernelEvaluations(), r.derivativeEvaluations(), bu == su ? "" : "<<<< DISAGREE");
            }
        System.out.println("agree " + agree + "/" + total);
        // pure N2
        var n2 = kernel(new double[]{126.192}, new double[]{3395800}, new double[]{0.0372});
        var sn = new TangentPlaneStability(n2);
        for (double P : new double[]{90e3, 120e3}) { var r = sn.test(77.355, P, new double[]{1}); System.out.println(P + " " + r); }
    }
}

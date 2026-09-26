import com.wormzjl.createcheme.science.thermo.*;
import java.util.*;

public class Scan {
    public static void main(String[] args) {
        var k = Scratch.kernel(new double[]{190.564, 126.192}, new double[]{4599200.0, 3395800}, new double[]{0.01142, 0.0372});
        var s = new TangentPlaneStability(k);
        var sw = s.newWorkspace();
        var ws = k.newWorkspace(); var ev = k.newEvaluation(); var el = k.newEvaluation();
        int total = 0, dis = 0, band = 0, unres = 0; long evals = 0; int maxIt = 0;
        Map<String,Integer> hist = new TreeMap<>();
        for (double T = 95; T <= 190; T += 5) for (double P = 0.1e6; P <= 5.0e6; P += 0.1e6) for (double zn = 0.02; zn < 0.99; zn += 0.04) {
            double[] z = {1 - zn, zn};
            var r = s.test(T, P, z, sw);
            k.evaluate(T, P, z, PengRobinsonKernel.Root.VAPOR, ws, ev); k.evaluate(T, P, z, PengRobinsonKernel.Root.LIQUID, ws, el);
            double gv = 0, gl = 0; for (int i = 0; i < 2; i++) { gv += z[i] * ev.logFugacityCoefficient(i); gl += z[i] * el.logFugacityCoefficient(i); }
            var fe = gl < gv ? el : ev;
            double[] d = new double[2]; for (int i = 0; i < 2; i++) d[i] = Math.log(z[i]) + fe.logFugacityCoefficient(i);
            double b = Scratch.brute(k, T, P, z, d);
            boolean bu = b < -1e-8; boolean su = r.verdict() == TangentPlaneStability.Verdict.UNSTABLE;
            total++; evals += r.kernelEvaluations(); maxIt = Math.max(maxIt, r.iterations());
            if (r.verdict() == TangentPlaneStability.Verdict.UNRESOLVED) unres++;
            if (bu != su) { if (Math.abs(b) < 1e-6) band++; else dis++;
                System.out.printf("T=%5.1f P=%4.2fMPa zN2=%.2f  %-10s tm=%+.3e brute D=%+.3e trials=%d it=%d ev=%d%n", T, P/1e6, zn, r.verdict(), r.minimumTangentPlaneDistance(), b, r.trials(), r.iterations(), r.kernelEvaluations()); }
        }
        System.out.printf("states %d, disagreements %d (+%d within |D|<1e-6), unresolved %d, mean evals %.1f, max iterations %d%n", total, dis, band, unres, evals/(double)total, maxIt);
    }
}

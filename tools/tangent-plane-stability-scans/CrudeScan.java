import com.wormzjl.createcheme.science.thermo.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

public class CrudeScan {
    public static void main(String[] args) {
        var catalog = MaterialCatalog.bundled();
        var pkg = catalog.requirePackage("createcheme:tjl20_methane_nitrogen");
        int n = pkg.components().size();
        double[] tc = new double[n], pc = new double[n], om = new double[n]; double[][] k = new double[n][n];
        for (int i = 0; i < n; i++) { var p = pkg.properties().get(i).pr(); tc[i] = p.criticalTemperature(); pc[i] = p.criticalPressure(); om[i] = p.acentricFactor();
            for (int j = 0; j < n; j++) k[i][j] = pkg.interactions().get(i).get(j); }
        var kernel = new PengRobinsonKernel(tc, pc, om, k, Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE, PengRobinsonKernel.Mixing.SPARSE_PAIRS);
        var a = pkg.assays().get("createcheme:tia_juana_light_methane");
        double[] z = new double[n];
        for (int c = 0; c < a.components().size(); c++) z[pkg.components().indexOf(a.components().get(c))] = a.amounts().get(c);
        z[pkg.components().indexOf("Nitrogen")] = 0.05;
        double[] light = new double[n]; for (int i = 0; i < 7; i++) light[i] = 0.1; light[19] = 0.3; for (int i = 7; i < 19; i++) light[i] = 1e-4;
        var s = new TangentPlaneStability(kernel); var sw = s.newWorkspace();
        var fluid = new FluidThermodynamics(catalog, "createcheme:tjl20_methane_nitrogen", 1e-9);
        int total = 0, agree = 0, unres = 0, flashFail = 0; long ev = 0; int maxEv = 0;
        for (double[] feed : new double[][]{z, light})
        for (double T = 300; T <= 900; T += 20) for (double P = 0.1e6; P <= 2.0e6 + 1; P += 0.1e6) {
            var r = s.test(T, P, feed, sw);
            ev += r.kernelEvaluations(); maxEv = Math.max(maxEv, r.kernelEvaluations());
            if (r.verdict() == TangentPlaneStability.Verdict.UNRESOLVED) unres++;
            int phases;
            try { var st = fluid.flashTP(T, P, Arrays.copyOf(feed, n + 1), () -> {}); double l = 0, v = 0; for (double x : st.liquidView()) l += x; for (double x : st.vaporView()) v += x; phases = (l > 0 ? 1 : 0) + (v > 0 ? 1 : 0); }
            catch (RuntimeException e) { flashFail++; continue; }
            total++;
            boolean ok = (phases == 2) == (r.verdict() == TangentPlaneStability.Verdict.UNSTABLE);
            if (ok) agree++; else System.out.printf("%s T=%.0f P=%.2f %s tm=%+.3e flash phases=%d%n", feed == z ? "crude" : "light", T, P/1e6, r.verdict(), r.minimumTangentPlaneDistance(), phases);
        }
        System.out.printf("compared %d agree %d unresolved %d flashFail %d mean evals %.1f max %d%n", total, agree, unres, flashFail, ev / (double) (total + flashFail), maxEv);
    }
}

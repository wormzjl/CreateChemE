package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Calibration of the mixture critical band on the P2 near-critical scan: for the 122 P2 failures and their grid
 * neighbours, the converged tie line and, for single-phase neighbours, the feed's stability-test stationary distance and
 * the smallest eigenvalue of the feed's reduced Hessian B = I + sqrt(z_i z_j) d ln phi_i/d n_j (zero on the spinodal,
 * which touches the phase boundary only at a critical point).
 */
public final class BandCalibration {
    public static void main(String[] args) throws Exception {
        Set<String> fixture = new HashSet<>();
        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("tools/phase-equilibrium-scans/p3/baseline-failures.txt"))) {
            String[] f = line.trim().split(" +");
            if (f.length > 3 && f[0].equals("FAIL")) fixture.add(f[1] + " " + f[2] + " " + f[3]);
        }
        var evaluator = PhaseTestSupport.methaneNitrogen();
        var service = new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator);
        var kernel = evaluator.kernel();
        var mixture = kernel.newWorkspace();
        var derivatives = kernel.newDerivatives();
        var w = service.newWorkspace();
        var test = new com.wormzjl.createcheme.science.thermo.TangentPlaneStability(kernel);
        var tw = test.newWorkspace();
        List<String> basis = List.of("Methane", "Nitrogen");
        int nt = 12, np = 176, nx = 50;
        double[][][] tie = new double[nt][np][nx];
        double[][][] lambda = new double[nt][np][nx];
        double[][][] stationary = new double[nt][np][nx];
        boolean[][][] split = new boolean[nt][np][nx];
        boolean[][][] fail = new boolean[nt][np][nx];
        for (int it = 0; it < nt; it++) {
            double t = 130 + 5 * it;
            for (int ip = 0; ip < np; ip++) {
                double p = 2.5e6 + 0.02e6 * ip;
                for (int ix = 0; ix < nx; ix++) {
                    double x = 0.01 + 0.02 * ix;
                    double[] z = {1 - x, x};
                    fail[it][ip][ix] = fixture.contains(String.format("%.0f %.0f %.2f", t, p / 1e3, x));
                    var r = service.tp(EquilibriumRequest.tp(t, p, basis, z, PhaseCompetition.FLUID_ONLY), w);
                    tie[it][ip][ix] = r.diagnostics().tieLine();
                    split[it][ip][ix] = r.classification() == EquilibriumResult.Classification.VAPOR_LIQUID;
                    // Feed on the stability root: the lower-Gibbs root at one pressure.
                    PengRobinsonKernel.Root root = r.phases().get(0).state().root() == com.wormzjl.createcheme.science.thermo.PhaseRoot.VAPOR
                            ? PengRobinsonKernel.Root.VAPOR : PengRobinsonKernel.Root.LIQUID;
                    if (split[it][ip][ix]) root = PengRobinsonKernel.Root.VAPOR;
                    lambda[it][ip][ix] = Double.NaN;
                    stationary[it][ip][ix] = Double.NaN;
                    if (!split[it][ip][ix]) stationary[it][ip][ix] = test.test(t, p, z, tw).nearestStationaryDistance();
                    if (!split[it][ip][ix]) {
                        try {
                            kernel.evaluateDerivatives(t, p, z, root, mixture, derivatives);
                            double a = 1 + z[0] * derivatives.dLogPhiDnRowView(0)[0];
                            double d = 1 + z[1] * derivatives.dLogPhiDnRowView(1)[1];
                            double b = Math.sqrt(z[0] * z[1]) * 0.5 * (derivatives.dLogPhiDnRowView(0)[1] + derivatives.dLogPhiDnRowView(1)[0]);
                            lambda[it][ip][ix] = 0.5 * (a + d) - Math.sqrt(0.25 * (a - d) * (a - d) + b * b);
                        } catch (IllegalStateException coalescence) {
                            lambda[it][ip][ix] = 0.0;
                        }
                    }
                }
            }
        }
        // Neighbours: single-phase states within one grid step (P or x, same T) of a failure.
        double worstLambda = 0, worstTie = 0;
        int neighbours = 0;
        double[] neighbourLambda = new double[20000];
        double[] neighbourStationary = new double[20000];
        for (int it = 0; it < nt; it++) for (int ip = 0; ip < np; ip++) for (int ix = 0; ix < nx; ix++) {
            if (fail[it][ip][ix]) worstTie = Math.max(worstTie, tie[it][ip][ix]);
            if (split[it][ip][ix]) continue;
            boolean near = false;
            for (int dp = -1; dp <= 1; dp++) for (int dx = -1; dx <= 1; dx++) {
                int jp = ip + dp, jx = ix + dx;
                if (jp >= 0 && jp < np && jx >= 0 && jx < nx && fail[it][jp][jx]) near = true;
            }
            if (near) {
                neighbourStationary[neighbours] = stationary[it][ip][ix];
                neighbourLambda[neighbours++] = lambda[it][ip][ix];
                worstLambda = Math.max(worstLambda, lambda[it][ip][ix]);
            }
        }
        double[] st = Arrays.copyOf(neighbourStationary, neighbours);
        Arrays.sort(st);
        int finite = 0; for (double v : st) if (Double.isFinite(v)) finite++;
        System.out.printf("neighbours: nearest stationary distance finite on %d of %d; min %.4g median %.4g max-finite %.4g%n", finite, neighbours, st[0], st[neighbours / 2], finite > 0 ? st[finite - 1] : Double.NaN);
        int allFinite = 0, below = 0; for (int it = 0; it < nt; it++) for (int ip = 0; ip < np; ip++) for (int ix = 0; ix < nx; ix++) { if (!split[it][ip][ix] && Double.isFinite(stationary[it][ip][ix])) { allFinite++; if (stationary[it][ip][ix] < 0.1) below++; } }
        System.out.printf("all single phases: finite stationary distance on %d, below 0.1 on %d%n", allFinite, below);
        double[] sorted = Arrays.copyOf(neighbourLambda, neighbours);
        Arrays.sort(sorted);
        System.out.printf("failures: tie line max %.4f; single-phase neighbours %d, lambda_min of B: min %.4g median %.4g p90 %.4g max %.4g%n",
                worstTie, neighbours, sorted[0], sorted[neighbours / 2], sorted[(int) (0.9 * neighbours)], sorted[neighbours - 1]);
        // Coverage of candidate thresholds over the whole scan.
        int singles = 0, splits = 0;
        for (int it = 0; it < nt; it++) for (int ip = 0; ip < np; ip++) for (int ix = 0; ix < nx; ix++) {
            if (split[it][ip][ix]) splits++; else singles++;
        }
        for (double threshold : new double[] {0.01, 0.02, 0.05, 0.1, 0.2, 0.3}) {
            int inTie = 0, inLambda = 0, neighbourIn = 0;
            for (int it = 0; it < nt; it++) for (int ip = 0; ip < np; ip++) for (int ix = 0; ix < nx; ix++) {
                if (split[it][ip][ix] && tie[it][ip][ix] < threshold) inTie++;
                if (!split[it][ip][ix] && lambda[it][ip][ix] < threshold) inLambda++;
            }
            for (int k = 0; k < neighbours; k++) if (sorted[k] < threshold) neighbourIn++;
            System.out.printf("  threshold %.2f: splits with tie < t %d of %d; single phases with lambda_min < t %d of %d; neighbours covered %d of %d%n",
                    threshold, inTie, splits, inLambda, singles, neighbourIn, neighbours);
        }
        // Per temperature: the lambda band's pressure extent for x_N2 at the failures' composition range.
        for (int it = 0; it < nt; it++) {
            int count = 0; double pmin = 1e300, pmax = 0;
            for (int ip = 0; ip < np; ip++) for (int ix = 0; ix < nx; ix++) {
                if (!split[it][ip][ix] && lambda[it][ip][ix] < 0.1) { count++; pmin = Math.min(pmin, 2.5 + 0.02 * ip); pmax = Math.max(pmax, 2.5 + 0.02 * ip); }
            }
            System.out.printf("  T %d K: single phases with lambda_min < 0.1: %d, P %.2f..%.2f MPa%n", 130 + 5 * it, count, pmin, pmax);
        }
    }
}

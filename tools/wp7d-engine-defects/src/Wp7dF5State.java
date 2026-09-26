package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.createcheme.science.thermo.TangentPlaneStability;
import java.util.Arrays;
import java.util.Locale;

/**
 * WP7d probe: the F5 state N2/CH4 0.67/0.33 at 150 K, 4.69 MPa on the pilot engine. Prints the engine's answer, the
 * feed's stability test at the default and larger iteration limits, and a brute-force tangent-plane scan of the binary
 * (both roots at every trial composition).
 */
public final class Wp7dF5State {
    static final String PILOT = "createcheme:pilot_cryogenic";

    public static void main(String[] args) {
        double t = args.length > 0 ? Double.parseDouble(args[0]) : 150.0;
        double p = args.length > 1 ? Double.parseDouble(args[1]) : 4.69e6;
        double xN2 = args.length > 2 ? Double.parseDouble(args[2]) : 0.67;
        var catalog = MaterialCatalog.bundled();
        var evaluator = CubicPhaseEvaluator.forPackage(catalog, PILOT);
        var engine = new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, PILOT), evaluator);
        var contract = engine.contract();
        System.out.println("contract " + contract.components() + " evaluator " + evaluator.components());
        double[] amounts = new double[contract.components().size()];
        amounts[contract.index("Nitrogen")] = xN2;
        amounts[contract.index("Methane")] = 1 - xN2;
        var result = engine.tp(EquilibriumRequest.tp(t, p, contract.components(), amounts, PhaseCompetition.FLUID_ONLY),
                engine.newWorkspace());
        System.out.println("engine: " + result.status() + " " + result.classification() + " band " + result.diagnostics().criticalBand()
                + " detail " + result.detail());
        System.out.println("  diagnostics " + result.diagnostics());
        for (var phase : result.phases()) {
            System.out.printf(Locale.ROOT, "  phase %s x %s v %.6e%n", phase.kind(), Arrays.toString(phase.amountsView()),
                    phase.state().molarVolume());
        }
        var kernel = evaluator.kernel();
        double[] feed = new double[evaluator.componentCount()];
        feed[evaluator.components().indexOf("Nitrogen")] = xN2;
        feed[evaluator.components().indexOf("Methane")] = 1 - xN2;
        for (int limit : new int[] {100, 300, 1000, 10000}) {
            var s = TangentPlaneStability.Settings.DEFAULT;
            var settings = new TangentPlaneStability.Settings(s.instabilityTolerance(), s.convergenceTolerance(), s.newtonSwitch(),
                    s.maximumSuccessiveSubstitutions(), s.accelerationCycle(), limit, s.trivialDistance(),
                    s.pureComponentTrialLimit(), true);
            var test = new TangentPlaneStability(kernel, settings);
            var r = test.test(t, p, feed, test.newWorkspace());
            System.out.printf(Locale.ROOT, "stability (limit %d, exhaustive): %s tm %.6e trial %s root %s feedRoot %s trials %d iterations %d stationary %.4e%n",
                    limit, r.verdict(), r.minimumTangentPlaneDistance(), Arrays.toString(r.trialComposition()), r.trialRoot(), r.feedRoot(),
                    r.trials(), r.iterations(), r.nearestStationaryDistance());
        }
        // Brute force: D(x) = sum x_i (ln x_i + ln phi_i(x) - d_i) on the lower-Gibbs root at each x_N2 in (0, 1).
        var ws = kernel.newWorkspace();
        var e1 = kernel.newEvaluation();
        var e2 = kernel.newEvaluation();
        int n2 = evaluator.components().indexOf("Nitrogen");
        int ch4 = evaluator.components().indexOf("Methane");
        kernel.evaluate(t, p, feed, PengRobinsonKernel.Root.VAPOR, ws, e1);
        kernel.evaluate(t, p, feed, PengRobinsonKernel.Root.LIQUID, ws, e2);
        System.out.printf(Locale.ROOT, "feed roots %d: Z_V %.8f Z_L %.8f gV %.10f gL %.10f%n", e1.physicalRootCount(), e1.compressibility(),
                e2.compressibility(), xN2 * e1.logFugacityCoefficient(n2) + (1 - xN2) * e1.logFugacityCoefficient(ch4),
                xN2 * e2.logFugacityCoefficient(n2) + (1 - xN2) * e2.logFugacityCoefficient(ch4));
        var feedEval = gibbs(e1, feed, n2, ch4) <= gibbs(e2, feed, n2, ch4) ? e1 : e2;
        double dN2 = Math.log(xN2) + feedEval.logFugacityCoefficient(n2);
        double dCH4 = Math.log(1 - xN2) + feedEval.logFugacityCoefficient(ch4);
        double min = Double.POSITIVE_INFINITY, argmin = Double.NaN;
        int steps = 20000;
        double[] trial = new double[feed.length];
        double previous = Double.NaN;
        StringBuilder minima = new StringBuilder();
        double prev2 = Double.NaN;
        for (int k = 1; k < steps; k++) {
            double x = (double) k / steps;
            trial[n2] = x;
            trial[ch4] = 1 - x;
            kernel.evaluate(t, p, trial, PengRobinsonKernel.Root.VAPOR, ws, e1);
            double best = x * (Math.log(x) + e1.logFugacityCoefficient(n2) - dN2) + (1 - x) * (Math.log(1 - x) + e1.logFugacityCoefficient(ch4) - dCH4);
            if (e1.physicalRootCount() > 1) {
                kernel.evaluate(t, p, trial, PengRobinsonKernel.Root.LIQUID, ws, e2);
                double other = x * (Math.log(x) + e2.logFugacityCoefficient(n2) - dN2) + (1 - x) * (Math.log(1 - x) + e2.logFugacityCoefficient(ch4) - dCH4);
                best = Math.min(best, other);
            }
            if (best < min) {
                min = best;
                argmin = x;
            }
            if (!Double.isNaN(prev2) && previous <= prev2 && previous <= best) {
                minima.append(String.format(Locale.ROOT, " local min D(%.5f) = %.4e;", (k - 1.0) / steps, previous));
            }
            prev2 = previous;
            previous = best;
        }
        System.out.printf(Locale.ROOT, "brute force over %d compositions: min D %.6e at x_N2 %.5f;%s%n", steps - 1, min, argmin, minima);
    }

    static double gibbs(PengRobinsonKernel.Evaluation e, double[] x, int a, int b) {
        return x[a] * e.logFugacityCoefficient(a) + x[b] * e.logFugacityCoefficient(b);
    }
}

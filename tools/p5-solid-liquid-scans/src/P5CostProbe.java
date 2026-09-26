import com.wormzjl.createcheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.createcheme.science.thermo.phase.PhaseCompetition;
import java.util.Locale;
import java.util.function.Supplier;

/** P5 cost probe (not a gate): warm per-call times of the crystal answers beside a liquid on a reused workspace. */
public final class P5CostProbe {
    static final int WARM = 200, RUNS = 1000;

    static void time(String name, Supplier<Object> call) {
        for (int k = 0; k < WARM; k++) call.get();
        long start = System.nanoTime();
        Object last = null;
        for (int k = 0; k < RUNS; k++) last = call.get();
        double us = (System.nanoTime() - start) / 1e3 / RUNS;
        String extra = last instanceof EquilibriumResult r ? r.classification() + ", " + r.diagnostics().outerIterations() + " TP, "
                + (r.deposition() == null ? "" : r.deposition().fluidEquilibria() + " fluid equilibria") : String.valueOf(last).substring(0, 40);
        System.out.printf(Locale.ROOT, "%-60s %9.1f us  (%s)%n", name, us, extra);
    }

    public static void main(String[] args) {
        var s = P5Probe.S;
        var ws = s.newWorkspace();
        var b = P5Probe.B;
        var c = PhaseCompetition.FLUID_AND_CRYSTALS;
        double[] ls = P5Probe.z(0, .95, 0, .05), ternary = P5Probe.z(.05, .85, 0, .1), vs = P5Probe.z(.9, 0, 0, .1), binary = P5Probe.z(0, .97, 0, .03);
        time("TP 5 % CO2 in liquid CH4, 130 K 1 MPa (LIQUID_SOLID)", () -> s.tp(EquilibriumRequest.tp(130, 1e6, b, ls, c), ws));
        time("TP 10 % CO2 in N2, 170 K 1 MPa (VAPOR_SOLID, P4 path)", () -> s.tp(EquilibriumRequest.tp(170, 1e6, b, vs, c), ws));
        time("TP ternary N2/CH4/CO2, 150 K 1.2 MPa", () -> s.tp(EquilibriumRequest.tp(150, 1.2e6, b, ternary, c), ws));
        time("TP 3 % CO2 binary, 165 K 1.9 MPa (next to its line)", () -> s.tp(EquilibriumRequest.tp(165, 1.9e6, b, binary, c), ws));
        time("TP fluid-only 5 % CO2 in CH4 at 230 K 1 MPa", () -> s.tp(EquilibriumRequest.tp(230, 1e6, b, ls, PhaseCompetition.FLUID_ONLY), ws));
        time("freezingTemperature 1 % CO2 in CH4 at 3 MPa", () -> s.freezingTemperature(null, 3e6, b, P5Probe.z(0, .99, 0, .01), ws));
        var r = s.tp(EquilibriumRequest.tp(130, 1e6, b, ls, c), ws);
        double h = r.enthalpy(), u = r.internalEnergy(), v = r.volume();
        time("PH of the 130 K liquid-solid state", () -> s.ph(EquilibriumRequest.ph(1e6, h, b, ls, c), ws));
        time("UV of the 130 K liquid-solid state", () -> s.uv(EquilibriumRequest.uv(u, v, b, ls, c), ws));
        var lo = s.tp(EquilibriumRequest.tp(160, 3e6, b, binary, c), ws);
        double u3 = lo.internalEnergy() - 500, v3 = lo.volume();
        time("UV of the 3 % binary on its three-phase line", () -> s.uv(EquilibriumRequest.uv(u3, v3, b, binary, c), ws));
    }
}

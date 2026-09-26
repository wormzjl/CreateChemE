package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.IdealGasFunction;
import com.wormzjl.createcheme.science.thermo.PengRobinson78;
import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import com.wormzjl.createcheme.science.thermo.TangentPlaneStability;
import com.wormzjl.createcheme.science.thermo.ThermoComponent;
import com.wormzjl.createcheme.science.thermo.phase.EquilibriumResult.Classification;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** WP6a scans: the P2 binary fields with band and Newton statistics, and the wet network field against flashTP. */
public final class P3Scan {
    static final Set<String> FIXTURE = new HashSet<>();

    public static void main(String[] args) throws Exception {
        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("tools/phase-equilibrium-scans/p3/baseline-failures.txt"))) {
            String[] f = line.trim().split(" +");
            if (f.length > 3 && f[0].equals("FAIL")) FIXTURE.add(f[1] + " " + f[2] + " " + f[3]);
        }
        String which = args.length > 0 ? args[0] : "all";
        if (which.equals("all") || which.equals("binary")) {
            binary("binary field", 95, 190, 5, 0.1e6, 5.0e6, 0.1e6, 0.02, 0.98, 0.04);
            binary("near-critical", 130, 185, 5, 2.5e6, 6.0e6, 0.02e6, 0.01, 0.99, 0.02);
        }
        if (which.equals("all") || which.equals("wet")) wet(args.length > 1 && args[1].equals("translated"));
    }

    static void binary(String name, double t0, double t1, double dt, double p0, double p1, double dp, double x0, double x1, double dx) {
        var evaluator = PhaseTestSupport.methaneNitrogen();
        var service = new FluidTpEquilibrium(PhaseTestSupport.openContract(evaluator), evaluator);
        var independent = new TangentPlaneStability(PhaseTestSupport.classicalKernel("Methane", "Nitrogen"));
        var w = service.newWorkspace();
        List<String> basis = List.of("Methane", "Nitrogen");
        Map<String, Integer> outcomes = new TreeMap<>();
        int states = 0, split = 0, newton = 0, newtonMax = 0, newtonSum = 0, bandSplit = 0, bandSingle = 0, bandPure = 0, merged = 0,
                ncIn = 0, ncOut = 0, fixtureSeen = 0, fixtureConverged = 0, fixtureBand = 0, fixtureNewton = 0, productBad = 0;
        long kernel = 0;
        int kernelMax = 0;
        double worstDefect = 0, worstFugacity = 0, fixtureTieMin = 1e300, fixtureTieMax = 0;
        long start = System.nanoTime();
        for (int it = 0; t0 + it * dt <= t1 + 1e-9; it++) {
            double t = t0 + it * dt;
            for (int ip = 0; p0 + ip * dp <= p1 + 1e-3; ip++) {
                double p = p0 + ip * dp;
                for (int ix = 0; x0 + ix * dx <= x1 + 1e-9; ix++) {
                    double x = x0 + ix * dx;
                    double[] z = {1 - x, x};
                    var r = service.tp(EquilibriumRequest.tp(t, p, basis, z, PhaseCompetition.FLUID_ONLY), w);
                    states++;
                    var d = r.diagnostics();
                    String key = r.status() + (r.converged() ? "/" + r.classification() : "");
                    outcomes.merge(key, 1, Integer::sum);
                    kernel += d.kernelEvaluations();
                    kernelMax = Math.max(kernelMax, d.kernelEvaluations());
                    String at = String.format("%.0f %.0f %.2f", t, p / 1e3, x);
                    boolean fixture = FIXTURE.contains(at);
                    if (fixture) fixtureSeen++;
                    if (!r.converged()) {
                        if (d.criticalBand()) ncIn++; else ncOut++;
                        System.out.println("  NOT_CONVERGED " + at + " band " + d.criticalBand() + ": " + r.detail());
                        continue;
                    }
                    worstDefect = Math.max(worstDefect, r.conservationDefect(z));
                    if (d.newtonIterations() > 0) {
                        newton++;
                        newtonSum += d.newtonIterations();
                        newtonMax = Math.max(newtonMax, d.newtonIterations());
                    }
                    boolean band = d.criticalBand();
                    if (r.classification() == Classification.VAPOR_LIQUID) {
                        split++;
                        if (band) bandSplit++;
                        worstFugacity = Math.max(worstFugacity, d.fugacityResidual());
                        for (var phase : r.phases()) {
                            if (independent.test(t, p, phase.amounts()).verdict() != TangentPlaneStability.Verdict.STABLE) productBad++;
                        }
                    } else if (band) {
                        if (r.detail().contains("merged")) merged++;
                        else if (r.coverage().evidence().contains("pure fluid")) bandPure++;
                        else bandSingle++;
                    }
                    if (fixture) {
                        fixtureConverged++;
                        if (band) fixtureBand++;
                        if (d.newtonIterations() > 0) fixtureNewton++;
                        fixtureTieMin = Math.min(fixtureTieMin, d.tieLine());
                        fixtureTieMax = Math.max(fixtureTieMax, d.tieLine());
                        System.out.printf("  FIXTURE %s %s band %s newton %d ss %d tie %.3e vapour %.4f fug %.2e kernel %d%n", at,
                                r.classification(), band, d.newtonIterations(), d.flashIterations(), d.tieLine(),
                                r.phases().size() > 1 ? r.phases().get(0).total() : Double.NaN, d.fugacityResidual(), d.kernelEvaluations());
                    }
                }
            }
        }
        System.out.printf("%s: %d states in %.1f s; %s; two-phase %d (in band %d); single in band: mixture %d, pure %d, merged %d; "
                        + "NOT_CONVERGED in band %d, outside %d; Newton used on %d splits (mean %.1f, max %d steps); kernel calls mean %.1f max %d; "
                        + "worst defect %.3g; worst fugacity %.3g; products not stable by the independent test %d%n",
                name, states, (System.nanoTime() - start) / 1e9, outcomes, split, bandSplit, bandSingle, bandPure, merged, ncIn, ncOut,
                newton, newton == 0 ? 0.0 : (double) newtonSum / newton, newtonMax, (double) kernel / states, kernelMax, worstDefect,
                worstFugacity, productBad);
        System.out.printf("  P2 failures in this scan: %d, now converged %d (in band %d, via Newton %d), tie line %.3e..%.3e%n",
                fixtureSeen, fixtureConverged, fixtureBand, fixtureNewton, fixtureTieMin, fixtureTieMax);
    }

    /** The network's current translations (HydrocarbonModel at 5100233, compressibility 1e-9). */
    static double[] networkTranslations() {
        var catalog = MaterialCatalog.bundled();
        var pkg = catalog.requirePackage(PhaseTestSupport.NETWORK);
        int n = pkg.components().size();
        List<ThermoComponent> components = new ArrayList<>();
        double[][] interactions = new double[n][n];
        for (int i = 0; i < n; i++) {
            var p = pkg.properties().get(i);
            components.add(new ThermoComponent(p.component(), p.pr().criticalTemperature(), p.pr().criticalPressure(),
                    p.pr().acentricFactor(), p.molecularWeight()));
            for (int j = 0; j < n; j++) interactions[i][j] = pkg.interactions().get(i).get(j);
        }
        var raw = new PengRobinson78(components, interactions);
        var calibrations = catalog.fluidData().volumeReferences();
        double k = 1e-9, ref = 2e6;
        double[] shifts = new double[n];
        for (int i = 0; i < n; i++) {
            var property = pkg.properties().get(i);
            var point = calibrations.get(property.component());
            double t = point == null ? property.standardTemperature() : point.temperatureKelvin();
            double target = point == null ? property.molecularWeight() / property.density() : point.molarVolumeCubicMetres();
            double refP = point == null ? property.standardPressure() : point.pressurePascal();
            double[] pure = new double[n];
            pure[i] = 1;
            double rawVolume = raw.evaluate(t, ref, pure, PhaseRoot.LIQUID).compressibilityFactor() * PengRobinson78.GAS_CONSTANT * t / ref;
            shifts[i] = target * Math.exp(k * (refP - ref)) - rawVolume;
        }
        return shifts;
    }

    static void wet(boolean translated) {
        var catalog = MaterialCatalog.bundled();
        var fluid = new FluidThermodynamics(catalog, PhaseTestSupport.NETWORK, 1.0e-9);
        FreeWaterModel water = PhaseTestSupport.networkFreeWater(fluid);
        var contract = PhaseContract.forNetworkPackage(catalog, PhaseTestSupport.NETWORK, ThermoIdentity.NO_SPINE);
        CubicPhaseEvaluator evaluator = PhaseTestSupport.networkEvaluator();
        if (translated) {
            var pkg = catalog.requirePackage(PhaseTestSupport.NETWORK);
            List<IdealGasFunction> gas = new ArrayList<>();
            for (int i = 0; i < evaluator.componentCount(); i++) gas.add(evaluator.idealGas(i));
            evaluator = CubicPhaseEvaluator.fromPackage(pkg, pkg.components(), networkTranslations(), gas);
        }
        var service = new FluidTpEquilibrium(contract, evaluator, FluidTpEquilibrium.Settings.DEFAULT, water);
        double[] crude = PhaseTestSupport.crudeWithNitrogen(contract);
        double[] gas = new double[contract.components().size()];
        for (String id : List.of("Methane", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane")) {
            gas[contract.index(id)] = 0.10;
        }
        gas[contract.index("Nitrogen")] = 0.30;
        for (int i = 0; i < gas.length; i++) if (contract.components().get(i).startsWith("crude_pc")) gas[i] = 1e-4;
        int wi = contract.waterIndex();
        var w = service.newWorkspace();
        Map<String, Integer> tally = new TreeMap<>();
        int states = 0, agree = 0, bothRefused = 0, hcDiffer = 0, waterDiffer = 0, statusDiffer = 0, printed = 0;
        long start = System.nanoTime();
        long engineNanos = 0, networkNanos = 0;
        for (double[] base : new double[][] {crude, gas}) {
            double hc = PhaseTestSupport.sum(base);
            for (double ratio : new double[] {0.001, 0.01, 0.1, 1.0, 10.0}) {
                for (int it = 0; it <= 30; it++) {
                    double t = 300 + 20 * it;
                    for (int ip = 1; ip <= 20; ip++) {
                        double p = 0.1e6 * ip;
                        double[] feed = base.clone();
                        feed[wi] = ratio * hc;
                        states++;
                        long a = System.nanoTime();
                        var r = service.tp(EquilibriumRequest.tp(t, p, contract.components(), feed, PhaseCompetition.FLUID_ONLY), w);
                        long b = System.nanoTime();
                        FluidThermodynamics.State flash = null;
                        String refusal = null;
                        try {
                            flash = fluid.flashTP(t, p, feed.clone(), () -> { });
                        } catch (IllegalArgumentException e) {
                            refusal = e.getMessage();
                        }
                        long c = System.nanoTime();
                        engineNanos += b - a;
                        networkNanos += c - b;
                        String detail = r.converged() ? " " + r.phases().stream().map(ph -> ph.kind().toString()).toList() : r.status() == EquilibriumResult.Status.OUT_OF_DOMAIN ? " " + r.violation().component() + " " + r.violation().property() : " " + (r.detail().length() > 50 ? r.detail().substring(0, 50) : r.detail());
                        tally.merge(r.status() + detail, 1, Integer::sum);
                        if (!r.converged() || flash == null) {
                            if (!r.converged() && flash == null) {
                                bothRefused++;
                                agree++;
                                continue;
                            }
                            statusDiffer++;
                            if (printed++ < 25) {
                                System.out.println("  status differs at " + t + " K " + p + " Pa r " + ratio + ": engine " + r.status() + " "
                                        + r.detail() + " / network " + (flash == null ? "refused: " + refusal : "ok"));
                            }
                            continue;
                        }
                        boolean nGas = PhaseTestSupport.sum(flash.vaporView()) > 0 || flash.waterVapor() > 0;
                        boolean nLiquid = PhaseTestSupport.sum(flash.liquidView()) > 0;
                        boolean nWater = flash.waterLiquid() > 0;
                        boolean eGas = false, eLiquid = false, eWater = false, eHcVapour = false;
                        for (var phase : r.phases()) {
                            switch (phase.kind()) {
                                case VAPOR -> {
                                    eGas = true;
                                    double s = 0;
                                    for (int i = 0; i < wi; i++) s += phase.amount(i);
                                    eHcVapour = s > 0;
                                }
                                case LIQUID -> eLiquid = true;
                                case FREE_WATER -> eWater = true;
                                default -> throw new IllegalStateException(phase.kind().toString());
                            }
                        }
                        double nHcVapour = PhaseTestSupport.sum(flash.vaporView());
                        int network = (nGas ? 1 : 0) + (nLiquid ? 1 : 0) + (nWater ? 1 : 0);
                        if (network == r.phases().size() && nGas == eGas && nLiquid == eLiquid && nWater == eWater) {
                            agree++;
                            continue;
                        }
                        boolean hcSame = nLiquid == eLiquid && (nHcVapour > 0) == eHcVapour;
                        if (!hcSame) hcDiffer++; else waterDiffer++;
                        if (printed++ < 25) {
                            System.out.printf("  %s at %.0f K %.1f MPa r %s: network gas %s liq %s water %s (nv %.3g wl %.3g wv %.3g pc %.6g) engine %s pc %.6g%n",
                                    hcSame ? "WATER-RULE" : "hydrocarbon split", t, p / 1e6, ratio, nGas, nLiquid, nWater, nHcVapour,
                                    flash.waterLiquid(), flash.waterVapor(), flash.hydrocarbonPartialPressure(),
                                    r.phases().stream().map(ph -> ph.kind().toString()).toList(), r.freeWater().hydrocarbonPressure());
                        }
                    }
                }
            }
        }
        System.out.printf("wet network (%s translations): %d states in %.1f s (engine %.0f us/state, flashTP %.0f us/state); phase counts agree %d "
                        + "(both refused %d); hydrocarbon split differs %d, water rule differs %d, status differs %d; outcomes %s%n",
                translated ? "network" : "zero", states, (System.nanoTime() - start) / 1e9, engineNanos / 1e3 / states,
                networkNanos / 1e3 / states, agree, bothRefused, hcDiffer, waterDiffer, statusDiffer, tally);
    }
}

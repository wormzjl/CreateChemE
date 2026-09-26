package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.science.column.v3.thermo.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/**
 * Serial, offline experiment: lagged-state hydraulic march, one bounded warm correction, in-ladder profile.
 * The resolver/calculator hooks are confined to the study classpath.
 */
public final class TrayPressureMethodStudy {
    static final Gson JSON = new GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues().create();
    static final double GAS_R = 8.314462618, WATER_MW = .01801528, FEED = 737.6996333000835, G = 9.80665;
    static final String PACKAGE = "createcheme:tjl19_dwsim";

    // ---- study hooks -------------------------------------------------------------------------------------
    static double[] override;          // explicit profile (resolver hook); disables the ladder hook
    static boolean ladder;             // calculator hook: every solve takes its profile from its own seed
    static int ladderCalls, ladderFallbacks;
    static long ladderNanos;
    static Hydraulics hydraulics;
    static V3PengRobinsonThermo thermo;
    static V3NeuralInitializer bundled;
    static double[] mw, rhoStd, tc, omega;

    static double[] pressureOverride(V3ColumnInput input) {
        if (override == null) return null;
        if (override.length != input.stageCount() + 2 || override[0] != input.topPressurePascal())
            throw new IllegalArgumentException("Study profile belongs to a different geometry/top pressure");
        return override.clone();
    }

    static V3NeuralSeed lastAccepted;  // last accepted state inside the running request (any rung geometry)

    static void ladderAccepted(V3ColumnProblem problem, V3DryMeshState state, String revision) {
        if (ladder && override == null) lastAccepted = V3NeuralSeed.capture(problem, state, revision);
    }

    /**
     * Calculator hook. The pressure profile of a solve is marched from the traffic of the last accepted state of
     * the same request, mapped section-wise onto this rung's trays; this problem's own authored steam is added
     * exactly. The first rung has no accepted state and keeps the nominal uniform profile.
     */
    static V3ColumnProblem ladderProblem(V3ColumnProblem problem, V3DryMeshState seed, String revision) {
        if (!ladder || override != null || lastAccepted == null) return problem;
        long start = System.nanoTime();
        try {
            ladderCalls++;
            V3ColumnInput in = problem.input();
            double[] p = march(in, traffic(in, mapped(lastAccepted, in)));
            if (System.getProperty("study.debugLadder") != null) System.out.printf(Locale.ROOT, "LADDER n=%d from n=%d drop/tray %.0f%n",
                    in.stageCount(), lastAccepted.input().stageCount(), (p[in.stageCount()] - p[1]) / Math.max(1, in.stageCount() - 1));
            double limit = thermo.maximumPressurePascal();
            for (double x : p) if (!Double.isFinite(x) || x <= 0 || x > limit) { ladderFallbacks++; return problem; }
            return new V3ColumnProblem(in, problem.topology(), problem.activeComponentBasis(),
                    problem.condenserComponentPhases(), p, problem.degreeOfFreedomLedger(),
                    problem.truncationSupport(), problem.wetTraySet());
        } catch (RuntimeException e) {
            ladderFallbacks++;
            return problem;
        } finally { ladderNanos += System.nanoTime() - start; }
    }

    /** Section-wise linear map of an accepted profile onto another tray count (feed tray onto feed tray). */
    static Profile mapped(V3NeuralSeed source, V3ColumnInput target) {
        V3ColumnInput from = source.input(); int n = target.stageCount(), m = from.stageCount();
        if (n == m && target.feedStageNumber() == from.feedStageNumber())
            return new Profile(source.liquid(), source.vapor(), source.temperatures(), source.freeWater());
        int count = mw.length; double[][] l = new double[n + 2][count], v = new double[n + 2][count];
        double[] t = new double[n + 2], w = new double[n + 2];
        int ff = from.feedStageNumber(), tf = target.feedStageNumber();
        for (int j = 0; j <= n + 1; j++) {
            double at = j == 0 ? 0 : j == n + 1 ? m + 1 : j <= tf ? (tf > 1 ? 1 + (j - 1.0) * (ff - 1) / (tf - 1) : 1)
                    : ff + (j - tf) * (double) (m - ff) / Math.max(1, n - tf);
            int lo = (int) Math.floor(at), hi = Math.min(m + 1, lo + 1); double x = at - lo;
            t[j] = source.temperatures()[lo] + x * (source.temperatures()[hi] - source.temperatures()[lo]);
            w[j] = source.freeWater()[lo] + x * (source.freeWater()[hi] - source.freeWater()[lo]);
            for (int c = 0; c < count; c++) {
                l[j][c] = source.liquid()[lo][c] + x * (source.liquid()[hi][c] - source.liquid()[lo][c]);
                v[j][c] = source.vapor()[lo][c] + x * (source.vapor()[hi][c] - source.vapor()[lo][c]);
            }
        }
        return new Profile(l, v, t, w);
    }

    // ---- traffic and hydraulics --------------------------------------------------------------------------
    /** Per-tray traffic leaving tray j. Water vapor = authored steam at/below j + free water from the tray above. */
    record Tray(double nV, double mV, double t, double mL, double vLstd, double tcL, double omL, double waterL) {}

    static double authoredSteam(V3ColumnInput i, int j) {
        return i.steamFeeds().stream().filter(s -> s.stageNumber() >= j).mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum();
    }
    record Profile(double[][] liquid, double[][] vapor, double[] temperatures, double[] freeWater) {}
    static Tray[] traffic(V3ColumnInput input, V3NeuralSeed s) {
        return traffic(input, new Profile(s.liquid(), s.vapor(), s.temperatures(), s.freeWater()));
    }
    static Tray[] traffic(V3ColumnInput input, Profile s) {
        int n = input.stageCount(); Tray[] out = new Tray[n + 2];
        double[][] v = s.vapor(), l = s.liquid(); double[] t = s.temperatures(), w = s.freeWater();
        for (int j = 1; j <= n; j++) {
            double water = authoredSteam(input, j) + (j >= 2 ? w[j - 1] : 0);
            double nv = 0, mv = 0, nl = 0, ml = 0, vl = 0, tcl = 0, oml = 0;
            for (int c = 0; c < mw.length; c++) {
                nv += v[j][c]; mv += v[j][c] * mw[c];
                nl += l[j][c]; ml += l[j][c] * mw[c]; vl += l[j][c] * mw[c] / rhoStd[c];
                tcl += l[j][c] * tc[c]; oml += l[j][c] * omega[c];
            }
            out[j] = new Tray(nv + water, mv + water * WATER_MW, t[j], ml, vl,
                    nl > 0 ? tcl / nl : 700, nl > 0 ? oml / nl : .5, w[j]);
        }
        return out;
    }

    interface Hydraulics { double drop(Tray tr, double p); String name(); }

    /** The Codex study correlation, unchanged (fixed 800 kg/m3 liquid, reference loads from the nominal run). */
    record CodexCorrelation(double vaporQ, double liquidQ, double vaporRho) implements Hydraulics {
        public double drop(Tray tr, double p) {
            if (tr.nV <= 0) return 100 + 200 * Math.pow(Math.max(0, tr.mL / 800 + tr.waterL * WATER_MW / 1000) / liquidQ, 2.0 / 3);
            double qv = tr.nV * GAS_R * tr.t / p, rho = tr.mV / qv, ql = tr.mL / 800 + tr.waterL * WATER_MW / 1000;
            return 100 + 450 * (rho / vaporRho) * Math.pow(qv / vaporQ, 2) + 200 * Math.pow(ql / liquidQ, 2.0 / 3);
        }
        public String name() { return "codex"; }
    }

    /** Sieve tray: orifice dry drop + Bennett, Agrawal and Cook (1983) clear liquid height + bubble residual. */
    record SieveTray(double diameter, double downcomerFraction, double holeFraction, double weirHeight,
                     double weirLengthRatio, double orificeCoefficient, double holeDiameter, double surfaceTension,
                     double traySpacing) implements Hydraulics {
        double area() { return Math.PI * diameter * diameter / 4; }
        double bubbling() { return (1 - 2 * downcomerFraction) * area(); }
        double net() { return (1 - downcomerFraction) * area(); }
        double[] terms(Tray tr, double p) {
            double rhoL = liquidDensity(tr), ql = tr.mL / rhoL + tr.waterL * WATER_MW / 1000;
            double rhoV = tr.nV > 0 ? tr.mV * p / (tr.nV * GAS_R * tr.t) : 1, qv = tr.nV > 0 ? tr.mV / rhoV : 0;
            double ub = qv / bubbling(), ks = ub * Math.sqrt(rhoV / Math.max(1, rhoL - rhoV));
            double ae = Math.exp(-12.55 * Math.pow(ks, .91));
            double c = .501 + .438 * Math.exp(-137.8 * weirHeight);
            double hcl = ae * (weirHeight + c * Math.pow(Math.max(0, ql) / (weirLengthRatio * diameter * ae), 2.0 / 3));
            double uh = ub / holeFraction, dry = rhoV * uh * uh / (2 * orificeCoefficient * orificeCoefficient);
            double bubble = 1.27 * Math.cbrt(holeDiameter * surfaceTension / (G * Math.max(1, rhoL - rhoV)));
            double residual = 6 * surfaceTension / bubble;
            double flv = tr.mV > 0 ? (tr.mL + tr.waterL * WATER_MW) / tr.mV * Math.sqrt(rhoV / rhoL) : 1;
            double csb = (.0105 + 8.127e-4 * Math.pow(traySpacing * 1000, .755) * Math.exp(-1.463 * Math.pow(flv, .842)))
                    * Math.pow(surfaceTension / .02, .2);
            double flood = (qv / net()) / (csb * Math.sqrt(Math.max(1e-9, (rhoL - rhoV) / rhoV)));
            return new double[]{dry, rhoL * G * hcl, residual, flood, ks, rhoL, rhoV};
        }
        public double drop(Tray tr, double p) { double[] x = terms(tr, p); return x[0] + x[1] + x[2]; }
        public String name() { return "sieve-bennett"; }
        SieveTray withDiameter(double d) {
            return new SieveTray(d, downcomerFraction, holeFraction, weirHeight, weirLengthRatio, orificeCoefficient,
                    holeDiameter, surfaceTension, traySpacing);
        }
    }
    /** Rackett temperature scaling of the standard (288.7 K) liquid density with Kay's-rule Tc and omega. */
    static double liquidDensity(Tray tr) {
        if (tr.vLstd <= 0) return 700;
        double std = tr.mL / tr.vLstd, zra = .29056 - .08775 * tr.omL;
        double tr0 = Math.min(.98, 288.706 / tr.tcL), trT = Math.min(.98, tr.t / tr.tcL);
        return std * Math.pow(zra, Math.pow(1 - tr0, 2.0 / 7) - Math.pow(1 - trT, 2.0 / 7));
    }

    /** Pressure-consistent downward march: each interval's drop is evaluated at its own lower-tray pressure. */
    static double[] march(V3ColumnInput input, Tray[] tr) {
        int n = input.stageCount(); double[] p = new double[n + 2]; p[0] = p[1] = input.topPressurePascal();
        for (int j = 2; j <= n; j++) {
            double pj = p[j - 1] + hydraulics.drop(tr[j], p[j - 1]);
            for (int k = 0; k < 50; k++) {
                double next = p[j - 1] + hydraulics.drop(tr[j], pj);
                boolean done = Math.abs(next - pj) < 1e-4; pj = next; if (done) break;
            }
            p[j] = pj;
        }
        p[n + 1] = p[n]; return p;
    }

    // ---- cases ------------------------------------------------------------------------------------------
    record Scenario(String id, int trays, double load, double temperature, double reflux, double dutyMW,
                    double steamFraction, double drawFraction, double coolerMW) {}
    static final Scenario[] DEV = {
        new Scenario("S01",10,.65,628.15,2.5,5,0,0,0), new Scenario("S02",16,.8,638.15,4.17,8,0,0,0),
        new Scenario("S03",24,1,648.15,4.17,8,0,0,0), new Scenario("S04",32,1.15,638.15,5,10,0,0,0),
        new Scenario("S05",40,1.3,628.15,3,10,0,0,0), new Scenario("S06",10,.65,628.15,2.5,3,.2,0,0),
        new Scenario("S07",16,.8,638.15,4.17,4,.3,0,0), new Scenario("S08",24,1,648.15,4.17,0,.4,0,0),
        new Scenario("S09",32,1.15,638.15,5,5,.25,.04,3), new Scenario("S10",40,1.3,628.15,3,4,.4,.06,5)};
    static final double[] DEV_PRESSURES = {75e3, 100e3, 150e3, 250e3, 350e3};
    static final Scenario[] VAL = {
        new Scenario("V01",12,.7,633.15,3,6,0,0,0), new Scenario("V02",20,.9,643.15,3.5,7,0,0,0),
        new Scenario("V03",28,1.1,633.15,4.5,9,0,0,0), new Scenario("V04",36,1.2,623.15,3.5,9,0,0,0),
        new Scenario("V05",14,.75,638.15,2,2,.15,0,0), new Scenario("V06",22,.95,643.15,3,3,.25,0,0),
        new Scenario("V07",30,1.05,648.15,4,0,.35,0,0), new Scenario("V08",26,1,638.15,4.5,6,.2,.05,4),
        new Scenario("V09",36,1.25,633.15,3.5,5,.3,.05,4), new Scenario("V10",18,.85,653.15,5,6,.1,.03,2)};
    static final double[] VAL_PRESSURES = {90e3, 130e3, 200e3, 300e3};

    static V3ColumnInput input(Scenario s, double pressure, double drop) {
        var crude = thermo.crudeFeed("createcheme:tia_juana_light"); double[] f = crude.moleFractions();
        for (int c = 0; c < f.length; c++) f[c] *= FEED * s.load;
        int feed = Math.max(2, (int) Math.round(.8 * s.trays));
        var draws = s.drawFraction == 0 ? List.<V3SideDrawSpec>of() : List.of(new V3SideDrawSpec(s.trays / 2, FEED * s.load * s.drawFraction));
        var steam = s.steamFraction == 0 ? List.<V3SteamFeedSpec>of() : List.of(new V3SteamFeedSpec(s.trays + 1, FEED * s.load * s.steamFraction, 533.15));
        var cool = s.coolerMW == 0 ? List.<V3PumparoundSpec>of() : List.of(new V3PumparoundSpec(s.trays / 2 - 2, s.trays / 2, -s.coolerMW * 1e6, V3PumparoundSpec.Split.UNIFORM));
        return new V3ColumnInput(1, crude.packageId(), crude.assayId(), crude.componentBasis(), f, s.temperature, s.trays, feed,
                pressure, drop, List.of(new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                new V3ColumnSpecification.OrganicRefluxRatio(s.reflux), new V3ColumnSpecification.ReboilerDuty(s.dutyMW * 1e6)), draws, steam, cool);
    }
    static V3ColumnInput withDrop(V3ColumnInput i, double drop) {
        return new V3ColumnInput(i.schemaVersion(), i.packageId(), i.assayId(), i.componentBasis(), i.feedComponentMolarFlowsMolPerSecond(),
                i.feedTemperatureKelvin(), i.stageCount(), i.feedStageNumber(), i.topPressurePascal(), drop,
                i.specifications(), i.sideDraws(), i.steamFeeds(), i.pumparounds());
    }

    // ---- solves -----------------------------------------------------------------------------------------
    record Run(V3ColumnInput input, String status, String detail, double ms, V3ColumnOutcome outcome,
               V3NeuralSeed seed, double[] pressures) {
        boolean success() { return seed != null && outcome instanceof V3ColumnOutcome.Success; }
    }
    static Run solve(V3ColumnInput input, double[] pressures, V3NeuralSeed warm, long budgetMs, boolean useLadder) {
        long started = System.nanoTime(); final V3NeuralSeed[] captured = {null}; V3ColumnOutcome outcome = null;
        String status = "ERROR", detail = ""; double[] used = pressures == null ? linearProfile(input) : pressures.clone();
        override = pressures; ladder = useLadder && pressures == null; lastAccepted = null;
        try {
            V3SolveControl control = () -> { if ((System.nanoTime() - started) / 1e6 >= budgetMs) throw new CancellationException("study deadline"); };
            V3NeuralInitializer model = bundled;
            V3InitializationOptions options = V3InitializationOptions.DEFAULT;
            if (warm != null) {
                var transferred = new V3NeuralSeed(input, warm.propertyRevision(), warm.branch(), warm.liquid(), warm.vapor(), warm.temperatures(), warm.freeWater(), warm.wetTrays());
                model = new V3NeuralInitializer() {
                    public String modelId() { return "study-previous-accepted-state"; }
                    public Optional<V3NeuralSeed> predict(V3ColumnInput ignored, V3SolveControl c) { c.checkpoint(); return Optional.of(transferred); }
                };
                options = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY, V3InitializationOptions.WetStart.AUTO, 64, (int) Math.min(10_000, budgetMs));
            }
            outcome = V3ColumnCalculator.calculateWithAcceptedProfile(input, control, options, model, seed -> captured[0] = seed);
            status = outcome.isSuccess() ? "SUCCESS" : ((V3ColumnOutcome.Failure) outcome).code().name();
            if (outcome instanceof V3ColumnOutcome.Failure f) detail = f.summary();
            if (outcome instanceof V3ColumnOutcome.Success success) {
                if (captured[0] == null || !success.result().acceptanceAudit().accepted()) throw new IllegalStateException("Missing accepted profile/audit");
                double[] published = success.result().problem().nodePressuresPascal();
                if (!ladder && !Arrays.equals(used, published)) throw new IllegalStateException("Solver ignored requested pressure profile");
                used = published;
            }
        } catch (CancellationException e) { status = "TIMEOUT"; detail = e.getMessage(); }
          catch (Exception e) { status = "ERROR"; detail = e.toString(); }
        finally { override = null; ladder = false; }
        return new Run(input, status, detail, (System.nanoTime() - started) / 1e6, outcome, captured[0], used);
    }
    static Run warm(Run from, double[] profile, long budgetMs) {
        int n = from.input.stageCount();
        return solve(withDrop(from.input, (profile[n] - profile[1]) / (n - 1)), profile, from.seed, budgetMs, false);
    }
    static double[] linearProfile(V3ColumnInput i) {
        double[] p = new double[i.stageCount() + 2]; p[0] = p[1] = i.topPressurePascal();
        for (int j = 2; j <= i.stageCount(); j++) p[j] = i.topPressurePascal() + (j - 1) * i.stagePressureDropPascal();
        p[p.length - 1] = p[p.length - 2]; return p;
    }
    static double[] marched(Run r) { return march(r.input, traffic(r.input, r.seed)); }

    static Map<String, Object> describe(Run r) {
        var m = new LinkedHashMap<String, Object>(); m.put("status", r.status); m.put("detail", r.detail); m.put("ms", r.ms);
        m.put("pressuresPa", r.pressures);
        if (r.outcome != null) {
            m.put("solvePath", r.outcome.diagnostics().solvePath());
            m.put("newtonIterations", r.outcome.diagnostics().newtonIterations());
            m.put("initializerEvent", r.outcome.diagnostics().events().stream().filter(e -> e.startsWith("initializer=")).findFirst().orElse(null));
        }
        if (r.success()) { m.put("temperaturesK", r.seed.temperatures()); m.put("traffic", traffic(r.input, r.seed)); }
        if (r.success() && System.getProperty("study.debugLadder") != null) {
            Tray[] dbg = traffic(r.input, r.seed); StringBuilder sb = new StringBuilder("ACCEPTED n=" + r.input.stageCount() + " nV:");
            for (int j = 1; j <= r.input.stageCount(); j++) sb.append(String.format(Locale.ROOT, " %.0f", dbg[j].nV));
            sb.append(" | mL:"); for (int j = 1; j <= r.input.stageCount(); j++) sb.append(String.format(Locale.ROOT, " %.0f", dbg[j].mL));
            System.out.println(sb);
        }
        return m;
    }
    static Map<String, Object> pressureMetrics(double[] used, double[] target) {
        double max = 0, pct = 0; int node = 0;
        for (int j = 1; j < used.length - 1; j++) {
            double e = Math.abs(used[j] - target[j]); if (e > max) { max = e; node = j; } pct = Math.max(pct, 100 * e / target[j]);
        }
        var m = new LinkedHashMap<String, Object>(); m.put("maxAbsPa", max); m.put("maxRelativePct", pct); m.put("worstTray", node);
        double td = target[target.length - 2] - target[1], ud = used[used.length - 2] - used[1];
        m.put("totalDropErrorPct", td > 0 ? 100 * (ud - td) / td : null); m.put("targetTotalDropPa", td); m.put("usedTotalDropPa", ud); return m;
    }
    static double mass(double[] a) { double x = 0; for (int c = 0; c < a.length; c++) x += a[c] * mw[c]; return x; }
    static Map<String, Object> errors(Run candidate, Run reference) {
        Map<String, Object> e = pressureMetrics(candidate.pressures, reference.pressures);
        double dt = 0; for (int j = 1; j < candidate.seed.temperatures().length; j++) dt = Math.max(dt, Math.abs(candidate.seed.temperatures()[j] - reference.seed.temperatures()[j]));
        e.put("maxTemperatureK", dt); double mf = mass(candidate.input.feedComponentMolarFlowsMolPerSecond());
        Map<String, V3ColumnStreamProperties> rs = new HashMap<>();
        for (var s : ((V3ColumnOutcome.Success) reference.outcome).result().streams()) rs.put(s.streamId(), s);
        double flow = 0, composition = 0;
        for (var s : ((V3ColumnOutcome.Success) candidate.outcome).result().streams()) {
            var r = rs.remove(s.streamId()); if (r == null) { e.put("productTopologyChanged", true); continue; }
            double comp = 0; Map<String, Double> fractions = new HashMap<>(); for (var c : r.moleFractions()) fractions.put(c.componentId(), c.moleFraction());
            for (var c : s.moleFractions()) comp = Math.max(comp, 100 * Math.abs(c.moleFraction() - fractions.getOrDefault(c.componentId(), 0.0)));
            flow = Math.max(flow, 100 * Math.abs(s.massFlowKgPerSecond() - r.massFlowKgPerSecond()) / mf); composition = Math.max(composition, comp);
        }
        if (!rs.isEmpty()) e.put("productTopologyChanged", true);
        e.put("maxProductMassErrorPctFeed", flow); e.put("maxCompositionPercentagePoints", composition); return e;
    }

    /** Undamped fixed point of the marched map, to 1 Pa. Its fixed point is the self-consistent hydraulic profile. */
    static Map<String, Object> reference(Run start, Run[] out) {
        long t0 = System.nanoTime(); Run current = start; var trace = new ArrayList<Map<String, Object>>(); String status = "OUTER_LIMIT";
        for (int k = 0; k < 15; k++) {
            double[] target = marched(current); var metrics = pressureMetrics(current.pressures, target);
            var step = new LinkedHashMap<String, Object>(); step.put("iteration", k); step.put("maxAbsPa", metrics.get("maxAbsPa")); trace.add(step);
            if ((double) metrics.get("maxAbsPa") <= 1.0) { status = "CONVERGED"; break; }
            Run next = warm(current, target, 10_000); step.put("correctorMs", next.ms); step.put("correctorStatus", next.status);
            if (!next.success()) {   // one damped retry keeps a hard case in the comparison
                double[] half = current.pressures.clone(); for (int j = 2; j < half.length; j++) half[j] += .5 * (target[j] - half[j]);
                next = warm(current, half, 10_000); step.put("dampedRetryStatus", next.status);
                if (!next.success()) { status = "CORRECTOR_" + next.status; break; }
            }
            current = next;
        }
        out[0] = current; var m = new LinkedHashMap<String, Object>(); m.put("status", status); m.put("ms", (System.nanoTime() - t0) / 1e6); m.put("trace", trace); return m;
    }

    static void loadPackage(String packageId) {
        thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
        var properties = MaterialCatalog.bundled().requirePackage(packageId).properties(); int count = properties.size();
        mw = new double[count]; rhoStd = new double[count]; tc = new double[count]; omega = new double[count];
        for (int c = 0; c < count; c++) {
            var p = properties.get(c); mw[c] = p.molecularWeight(); rhoStd[c] = p.density();
            tc[c] = p.pr().criticalTemperature(); omega[c] = p.pr().acentricFactor();
            if (Math.abs(mw[c] - thermo.componentMolecularWeightKgPerMol(c)) > 1e-12) throw new IllegalStateException("Component order mismatch");
        }
    }

    /** Sizes the default diameter from the shipped column presets, then runs the arms on each with that diameter. */
    static void presets(Path dir) throws Exception {
        var catalog = MaterialCatalog.bundled(); SieveTray base = new SieveTray(1, .10, .10, .05, .726, .78, .0127, .02, .6);
        double chosen = Double.parseDouble(System.getProperty("study.diameter", "0"));
        try (var writer = Files.newBufferedWriter(dir.resolve("presets.jsonl"))) {
            for (var preset : catalog.presets().columns().values()) {
                V3ColumnInput in = preset.input(catalog); loadPackage(in.packageId()); hydraulics = base;
                var row = new LinkedHashMap<String, Object>(); row.put("id", preset.descriptor().id()); row.put("package", in.packageId());
                row.put("trays", in.stageCount()); row.put("topPressurePa", in.topPressurePascal()); row.put("authoredDropPa", in.stagePressureDropPascal());
                Run fixed = null; for (int k = 0; k < 2; k++) fixed = solve(in, null, null, 60_000, false);   // second run is JIT-warm
                row.put("fixed", fixed.status); row.put("fixedMs", fixed.ms);
                if (fixed.success()) {
                    Tray[] tr = traffic(in, fixed.seed); double worst = 0; int at = 0;
                    for (int j = 2; j <= in.stageCount(); j++) { double f = base.terms(tr[j], fixed.pressures[j])[3]; if (f > worst) { worst = f; at = j; } }
                    row.put("worstTray", at); row.put("diameterAt80PctFlood", Math.sqrt(worst / .80)); row.put("diameterAt70PctFlood", Math.sqrt(worst / .70));
                    if (chosen > 0) {
                        SieveTray sized = base.withDiameter(chosen); hydraulics = sized; double[] p1 = marched(fixed); int n = in.stageCount();
                        row.put("diameter", chosen); row.put("marchedDropPaPerTray", (p1[n] - p1[1]) / (n - 1)); row.put("marchedTotalDropPa", p1[n] - p1[1]);
                        var terms = new ArrayList<double[]>(); for (int j = 2; j <= n; j++) terms.add(sized.terms(tr[j], p1[j])); row.put("termsDryLiquidResidualFloodKsRhoLRhoV", terms);
                        Run c1 = warm(fixed, p1, 20_000); row.put("oneShot", c1.status); row.put("oneShotMs", c1.ms);
                        if (c1.success()) {
                            row.put("oneShotResidualMismatch", pressureMetrics(c1.pressures, marched(c1)));
                            Run[] out = {null}; var ref = reference(c1, out); row.put("reference", ref.get("status"));
                            if ("CONVERGED".equals(ref.get("status"))) { row.put("oneShotErrors", errors(c1, out[0])); row.put("fixedErrors", errors(fixed, out[0])); }
                        }
                        Run lad = solve(in, null, null, 60_000, true); row.put("ladder", lad.status); row.put("ladderMs", lad.ms);
                        if (lad.success()) row.put("ladderSelfMismatch", pressureMetrics(lad.pressures, marched(lad)));
                    }
                }
                writer.write(JSON.toJson(row)); writer.newLine(); writer.flush();
                var brief = new LinkedHashMap<>(row); brief.remove("termsDryLiquidResidualFloodKsRhoLRhoV"); System.out.println("PRESET " + JSON.toJson(brief));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0]; Path dir = Path.of(args[1]); Files.createDirectories(dir);
        bundled = V3NeuralModels.bundled();
        if (mode.equals("presets")) { presets(dir); return; }
        loadPackage(PACKAGE);
        boolean dev = mode.startsWith("dev"), pilot = mode.endsWith("pilot");
        Scenario[] scenarios = dev ? DEV : VAL; double[] pressures = dev ? DEV_PRESSURES : VAL_PRESSURES;
        var nominalInput = input(new Scenario("NOM", 20, 1, 638.15, 4.17, 8, 0, 0, 0), 250_000, 750);
        Run nominal = null;
        for (int k = 0; k < 2; k++) {
            nominal = solve(nominalInput, null, null, 30_000, false);
            System.out.printf(Locale.ROOT, "WARMUP %d %s %.0f ms%n", k, nominal.status, nominal.ms);
            if (!nominal.success()) throw new IllegalStateException("Nominal solve failed: " + nominal.detail);
        }
        var meta = new LinkedHashMap<String, Object>(); meta.put("mode", mode); meta.put("java", System.getProperty("java.version"));
        Tray[] nominalTraffic = traffic(nominalInput, nominal.seed); int nn = nominalInput.stageCount();
        if (dev) {
            double qv = 0, ql = 0, rho = 0; double[] np = nominal.pressures;
            for (int j = 2; j <= nn; j++) {
                Tray tr = nominalTraffic[j]; double q = tr.nV * GAS_R * tr.t / np[j];
                qv += q; ql += tr.mL / 800 + tr.waterL * WATER_MW / 1000; rho += tr.mV / q;
            }
            hydraulics = new CodexCorrelation(qv / (nn - 1), ql / (nn - 1), rho / (nn - 1));
        } else {
            SieveTray base = new SieveTray(1, .10, .10, .05, .726, .78, .0127, .02, .6); double worst = 0;
            for (int j = 2; j <= nn; j++) worst = Math.max(worst, base.terms(nominalTraffic[j], nominal.pressures[j])[3]);
            SieveTray sized = base.withDiameter(Math.sqrt(worst / .75)); hydraulics = sized;   // 75 % of flood at the nominal worst tray
            double[] p = marched(nominal); meta.put("nominalMarchedDropPaPerTray", (p[nn] - p[1]) / (nn - 1));
            var rows = new ArrayList<double[]>(); for (int j = 2; j <= nn; j++) rows.add(sized.terms(nominalTraffic[j], p[j])); meta.put("nominalTerms", rows);
        }
        meta.put("hydraulics", hydraulics); meta.put("hydraulicsName", hydraulics.name()); meta.put("datasetRevision", thermo.datasetRevision());
        meta.put("model", bundled.modelId()); meta.put("scenarios", scenarios); meta.put("pressuresPa", pressures);
        Files.writeString(dir.resolve("metadata.json"), JSON.toJson(meta));
        System.out.println("HYDRAULICS " + JSON.toJson(hydraulics));
        Path journal = dir.resolve("cases.jsonl"); if (Files.exists(journal)) throw new IllegalStateException("Refuse to overwrite journal");
        int index = 0;
        try (var writer = Files.newBufferedWriter(journal)) {
            for (double pressure : pressures) for (Scenario s : scenarios) {
                int i = index++; if (pilot && i % 7 != 3) continue; if (System.getProperty("study.only") != null && !String.format(Locale.ROOT, "P%03d-%s", (int) (pressure / 1000), s.id).equals(System.getProperty("study.only"))) continue;
                var row = new LinkedHashMap<String, Object>(); String id = String.format(Locale.ROOT, "P%03d-%s", (int) (pressure / 1000), s.id);
                row.put("id", id); row.put("scenario", s); row.put("topPressurePa", pressure);
                try { runCase(row, input(s, pressure, 750), i); } catch (Exception failure) { row.put("studyFailure", failure.toString()); }
                writer.write(JSON.toJson(row)); writer.newLine(); writer.flush();
                System.out.printf(Locale.ROOT, "CASE %s %s%n", id, row.get("summary"));
            }
        }
        System.out.println("COMPLETE -> " + journal.toAbsolutePath());
    }

    static void runCase(Map<String, Object> row, V3ColumnInput request, int index) {
        Run fixed, lad; ladderCalls = ladderFallbacks = 0; ladderNanos = 0;
        if (index % 2 == 0) { fixed = solve(request, null, null, 30_000, false); lad = solve(request, null, null, 30_000, true); }
        else { lad = solve(request, null, null, 30_000, true); fixed = solve(request, null, null, 30_000, false); }
        row.put("fixed", describe(fixed)); row.put("ladder", describe(lad));
        row.put("ladderHook", Map.of("calls", ladderCalls, "fallbacks", ladderFallbacks, "ms", ladderNanos / 1e6));
        var summary = new LinkedHashMap<String, Object>(); summary.put("fixed", fixed.status); summary.put("ladder", lad.status); row.put("summary", summary);
        // Arm B: fresh solve at the nominal drop, one march, one undamped warm correction.
        Run oneShot = null;
        if (fixed.success()) {
            long t0 = System.nanoTime(); double[] p1 = marched(fixed); double marchMs = (System.nanoTime() - t0) / 1e6;
            var b = new LinkedHashMap<String, Object>(); b.put("marchMs", marchMs); b.put("fixedMismatch", pressureMetrics(fixed.pressures, p1));
            Run c1 = warm(fixed, p1, 10_000); b.put("correction", describe(c1));
            if (!c1.success()) {
                double[] half = fixed.pressures.clone(); for (int j = 2; j < half.length; j++) half[j] += .5 * (p1[j] - half[j]);
                Run h = warm(fixed, half, 10_000); b.put("halfStep", describe(h));
                if (h.success()) { c1 = warm(h, marched(h), 10_000); b.put("afterHalfStep", describe(c1)); }
            }
            if (c1.success()) { oneShot = c1; b.put("residualMismatch", pressureMetrics(c1.pressures, marched(c1))); }
            row.put("oneShot", b); summary.put("oneShot", c1.status);
        }
        // Arm C: in-ladder profile; correction only when the accepted state disagrees with its own profile.
        Run ladderFinal = null;
        if (lad.success()) {
            var c = new LinkedHashMap<String, Object>(); double[] pm = marched(lad); var mismatch = pressureMetrics(lad.pressures, pm);
            c.put("mismatch", mismatch); ladderFinal = lad;
            Object total = mismatch.get("totalDropErrorPct");
            if (total != null && Math.abs((double) total) > 5.0) {
                Run corrected = warm(lad, pm, 10_000); c.put("correction", describe(corrected));
                if (corrected.success()) { ladderFinal = corrected; c.put("residualMismatch", pressureMetrics(corrected.pressures, marched(corrected))); }
            }
            row.put("ladderTriggered", c);
        }
        Run start = oneShot != null ? oneShot : ladderFinal != null ? ladderFinal : fixed.success() ? fixed : null;
        if (start == null) return;
        Run[] out = {null}; var ref = reference(start, out); row.put("reference", ref);
        if (!"CONVERGED".equals(ref.get("status"))) { summary.put("reference", ref.get("status")); row.put("summary", summary); return; }
        Run reference = out[0]; row.put("referenceState", describe(reference));
        if (hydraulics instanceof SieveTray sieve) {
            double flood = 0; Tray[] tr = traffic(reference.input, reference.seed);
            for (int j = 2; j <= reference.input.stageCount(); j++) flood = Math.max(flood, sieve.terms(tr[j], reference.pressures[j])[3]);
            row.put("referenceMaxFloodFraction", flood); summary.put("flood", Math.round(flood * 100) / 100.0);
        }
        var errors = new LinkedHashMap<String, Object>();
        if (fixed.success()) errors.put("fixed", errors(fixed, reference));
        if (oneShot != null) errors.put("oneShot", errors(oneShot, reference));
        if (lad.success()) errors.put("ladderRaw", errors(lad, reference));
        if (ladderFinal != null) errors.put("ladderTriggered", errors(ladderFinal, reference));
        row.put("errors", errors);
        for (var e : errors.entrySet()) summary.put(e.getKey() + "%", Math.round(10 * (double) ((Map<?, ?>) e.getValue()).get("totalDropErrorPct")) / 10.0);
        row.put("summary", summary);
    }
}

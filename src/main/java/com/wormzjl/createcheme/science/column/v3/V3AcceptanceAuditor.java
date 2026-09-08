package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3WaterProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Recomputes dry physical acceptance from a candidate state; it never accepts a solver-cached residual vector. */
final class V3AcceptanceAuditor {
    private static final double EQUILIBRIUM_LIMIT = 1.0e-8;
    /** Absolute phase-flow fraction tolerance; this verifies a solved split and never waives an appearing phase. */
    private static final double CONDENSER_PHASE_SPLIT_LIMIT = 1.0e-8;
    private static final double TRUNCATION_DEFECT_BUDGET = 8.0;

    private final V3ColumnProblem problem;
    private final V3ThermoModel thermo;
    private final double feedMolarEnthalpyJoulesPerMol;
    private final double closureTolerance;

    V3AcceptanceAuditor(V3ColumnProblem problem, V3ThermoModel thermo, double feedMolarEnthalpyJoulesPerMol) {
        this(problem, thermo, feedMolarEnthalpyJoulesPerMol, V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE);
    }

    /**
     * Audits against the convergence closure the candidate was solved to.
     *
     * <p>Only the limits that are closure statements move with it: the equilibrium row family, the condenser
     * phase split, and the two independently recomputed energy closures. The local component and tray energy
     * row families keep their limit of one, and the two truncation defect budgets are mass budgets of the
     * flow floor, not of the Newton stop, and keep theirs.</p>
     */
    V3AcceptanceAuditor(
            V3ColumnProblem problem, V3ThermoModel thermo, double feedMolarEnthalpyJoulesPerMol,
            double closureTolerance) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.thermo = Objects.requireNonNull(thermo, "thermo");
        if (!problem.input().componentBasis().equals(thermo.componentBasis()) || !Double.isFinite(feedMolarEnthalpyJoulesPerMol)) {
            throw new IllegalArgumentException("V3 acceptance auditor does not match its problem and thermodynamic model");
        }
        this.feedMolarEnthalpyJoulesPerMol = feedMolarEnthalpyJoulesPerMol;
        this.closureTolerance = V3ConvergenceEvidence.requireClosure(closureTolerance);
    }

    /** Equilibrium and condenser-split limit: the frozen absolute floor, loosened only by an authored closure. */
    private double closureLimit() {
        return Math.max(EQUILIBRIUM_LIMIT, closureTolerance);
    }

    /** The residual evaluator's own energy row scale, {@code max(1, F_total * 1e5 W)}. */
    private double energyScale() {
        return Math.max(1.0, problem.activeComponentBasis().totalFeedFlowMolPerSecond() * 100_000.0);
    }

    V3AcceptanceAudit audit(V3DryMeshState state, V3ThermoWorkspace workspace) {
        return audit(state, workspace, V3SolveControl.UNBOUNDED);
    }

    V3AcceptanceAudit audit(V3DryMeshState state, V3ThermoWorkspace workspace, V3SolveControl control) {
        state = Objects.requireNonNull(state, "state");
        workspace = Objects.requireNonNull(workspace, "workspace");
        control = Objects.requireNonNull(control, "control");
        control.checkpoint();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, feedMolarEnthalpyJoulesPerMol);
        V3MeshResidual residual = evaluator.evaluate(state, workspace);
        control.checkpoint();
        List<V3AcceptanceAudit.Check> checks = new ArrayList<>();
        checks.add(finitenessAndTopology(state));
        if (problem.hasSideDraws()) checks.add(sideDrawSplit(state));
        if (problem.hasSteamFeeds()) {
            checks.add(waterProfile(state));
            checks.add(waterBalance(state));
            checks.add(waterDewPoint(state));
            if (problem.topology().condenserPhaseBranch() == V3CondenserPhaseBranch.TWO_PHASE
                    && problem.hasFreeWaterCondenser()) {
                checks.add(freeWaterSplit(state));
            }
        }
        checks.add(maximumFamily(residual, V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE,
                "LOCAL_COMPONENT_BALANCE", 1.0));
        checks.add(maximumFamily(residual, V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM,
                "EQUILIBRIUM", closureLimit()));
        checks.add(maximumFamily(residual, V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE,
                "ENERGY_BALANCE", 1.0));
        if (problem.truncationSupport().truncatedPointCount() > 0) checks.add(truncationMassDefect(state));
        if (problem.truncationSupport().onePhasePointCount() > 0) {
            checks.add(phaseTruncationDefect(state, workspace));
        }
        control.checkpoint();
        if (problem.topology().condenserPhaseBranch() == V3CondenserPhaseBranch.LIQUID_ONLY) {
            checks.add(liquidCondenserPhase(state, workspace));
        } else if (problem.topology().condenserPhaseBranch() == V3CondenserPhaseBranch.TWO_PHASE) {
            checks.add(twoPhaseCondenserSplit(state, workspace));
        }
        // The whole-column boundary closure guards the stage-heat sign and, on a wet column, the steam
        // enthalpy in against the water leaving tray one. The condenser node is closed by definition in
        // that sum, so its water outlets are guarded separately against the published condenser duty.
        if (problem.hasPumparounds() || problem.hasSteamFeeds()) {
            checks.add(globalEnergyBalance(state, evaluator, workspace));
        }
        if (problem.hasSteamFeeds()) {
            checks.add(condenserEnergyBalance(problem, thermo, state, workspace,
                    V3ColumnDutyLedger.condenserDutyWatts(problem, state, evaluator, workspace), closureTolerance));
        }
        control.checkpoint();
        List<String> advisoryEvidence = thermo instanceof V3PengRobinsonThermo registeredPackage
                ? registeredPackage.advisoryEvidence() : List.of();
        if (problem.hasPumparounds()) advisoryEvidence = withCooledTrayAdvisory(advisoryEvidence, state);
        advisoryEvidence = withWetTrayAdvisory(advisoryEvidence, state);
        advisoryEvidence = withDewPointAdvisory(advisoryEvidence, checks);
        return new V3AcceptanceAudit(checks, advisoryEvidence);
    }

    /**
     * Independently closes the whole-column energy balance from boundary streams only.
     *
     * <p>The prescribed stage heat is re-expanded from the authored input with the documented sign, so a
     * residual assembled with the opposite sign cannot pass here: the row-residual {@code ENERGY_BALANCE}
     * check would be consistently wrong on both sides of every tray equation and could not detect it.</p>
     *
     * <p>On a wet column the same closure carries the authored steam enthalpy in against the water leaving
     * tray one, so a dropped or double-counted tray or sump water term fails here. It does not guard the
     * condenser side: the condenser duty is defined as that node's closure, so the condenser outlets are
     * added inside the duty and subtracted again as products and cancel exactly. That side is covered by
     * {@link #condenserEnergyBalance}.</p>
     */
    private V3AcceptanceAudit.Check globalEnergyBalance(
            V3DryMeshState state, V3MeshResidualEvaluator evaluator, V3ThermoWorkspace workspace) {
        V3ColumnTopology topology = problem.topology();
        double[] liquidEnergy = new double[topology.nodeCount()];
        double[] vaporEnergy = new double[topology.nodeCount()];
        for (int node = 0; node < topology.nodeCount(); node++) {
            V3MeshResidualEvaluator.LocalNodeTerms terms = evaluator.localTerms(state, node, workspace);
            liquidEnergy[node] = terms.liquidPhaseEnergy();
            vaporEnergy[node] = terms.vaporPhaseEnergy();
        }
        double[] stageHeat = V3Pumparounds.nodeDutyWatts(problem.input(), topology);
        double stageHeatTotal = 0.0;
        for (double duty : stageHeat) stageHeatTotal += duty;
        double freeWater = V3ColumnDutyLedger.freeWaterEnergyWatts(problem, state);
        double condenser = liquidEnergy[0] + vaporEnergy[0] + freeWater - vaporEnergy[1];
        double reflux = refluxRatio() / (1.0 + refluxRatio());
        double feed = problem.activeComponentBasis().totalFeedFlowMolPerSecond() * feedMolarEnthalpyJoulesPerMol;
        double reboiler = V3ColumnDutyLedger.reboilerDutyWatts(problem);
        double steam = V3ColumnDutyLedger.steamEnthalpyWatts(problem);
        double distillate = (1.0 - reflux) * liquidEnergy[0];
        double bottoms = liquidEnergy[topology.reboilerNode()];
        double sideDraws = 0.0;
        for (V3SideDrawSpec draw : problem.input().sideDraws()) {
            sideDraws += V3SideDraws.withdrawal(state, draw.trayNumber(), draw.molarFlowMolPerSecond()).fraction()
                    * liquidEnergy[draw.trayNumber()];
        }
        double closure = feed + reboiler + steam + stageHeatTotal + condenser
                - distillate - vaporEnergy[0] - freeWater - sideDraws - bottoms;
        double largest = 0.0;
        for (double term : new double[] {feed, reboiler, steam, stageHeatTotal, condenser, distillate,
                vaporEnergy[0], freeWater, sideDraws, bottoms}) {
            largest = Math.max(largest, Math.abs(term));
        }
        // The boundary closure is the sum of the tray closures, so an authored convergence closure of tau on
        // every one of the nodeCount energy rows admits up to nodeCount * tau * (the row scale) here.
        double limit = Math.max(Math.max(1.0, 1.0e-6 * largest),
                topology.nodeCount() * closureTolerance * energyScale());
        double magnitude = Math.abs(closure);
        String detail = String.format(Locale.ROOT,
                "fresh boundary closure %.6g W; condenser=%.6g W, stage heat=%.6g W", closure, condenser, stageHeatTotal);
        return Double.isFinite(magnitude) && magnitude <= limit
                ? V3AcceptanceAudit.Check.pass("GLOBAL_ENERGY_BALANCE", magnitude, limit, detail)
                : V3AcceptanceAudit.Check.fail("GLOBAL_ENERGY_BALANCE",
                        Double.isFinite(magnitude) ? magnitude : Double.MAX_VALUE, limit, detail);
    }

    /**
     * Independently closes the condenser node and compares the result with the published condenser duty.
     *
     * <p>Every term is rebuilt from the authored input, the candidate state, and direct property calls: the
     * hydrocarbon outlets and the arriving tray-one vapor use {@link V3ThermoModel#molarEnthalpy} on the
     * candidate flows, the water arriving at the condenser is the authored steam total, and the vapor and
     * free-water outlets come from the auditor's own regime split. Nothing here reuses the residual
     * evaluator's condenser terms, so a condenser vapor outlet that drops or double-counts the water it
     * publishes fails this check instead of silently shifting the duty the ledger reports.</p>
     */
    static V3AcceptanceAudit.Check condenserEnergyBalance(
            V3ColumnProblem problem, V3ThermoModel thermo, V3DryMeshState state, V3ThermoWorkspace workspace,
            double publishedCondenserWatts) {
        return condenserEnergyBalance(problem, thermo, state, workspace, publishedCondenserWatts,
                V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE);
    }

    static V3AcceptanceAudit.Check condenserEnergyBalance(
            V3ColumnProblem problem, V3ThermoModel thermo, V3DryMeshState state, V3ThermoWorkspace workspace,
            double publishedCondenserWatts, double closureTolerance) {
        V3ConvergenceEvidence.requireClosure(closureTolerance);
        V3ColumnTopology topology = problem.topology();
        int condenser = topology.condenserNode();
        double outletTemperature = state.temperatureKelvin(condenser);
        double liquidOut = topology.hasLiquidPhase(condenser)
                ? hydrocarbonPhaseEnergyWatts(problem, thermo, state, condenser, true, workspace) : 0.0;
        double vaporOut = topology.hasVaporPhase(condenser)
                ? hydrocarbonPhaseEnergyWatts(problem, thermo, state, condenser, false, workspace) : 0.0;
        IndependentWaterSplit water = independentCondenserWaterSplit(problem, state);
        double waterVaporOut = water.vaporFlowMolPerSecond() == 0.0 ? 0.0
                : water.vaporFlowMolPerSecond() * V3WaterProperties.vaporMolarEnthalpy(outletTemperature);
        double freeWaterOut = water.freeWaterFlowMolPerSecond() == 0.0 ? 0.0
                : water.freeWaterFlowMolPerSecond() * V3WaterProperties.liquidMolarEnthalpy(outletTemperature);
        double arrivingWater = authoredWaterAtCondenser(problem);
        double vaporIn = hydrocarbonPhaseEnergyWatts(problem, thermo, state, 1, false, workspace)
                + (arrivingWater == 0.0 ? 0.0 : arrivingWater * V3WaterProperties.vaporMolarEnthalpy(state.temperatureKelvin(1)));
        double expected = liquidOut + vaporOut + waterVaporOut + freeWaterOut - vaporIn;
        double largest = 0.0;
        for (double term : new double[] {liquidOut, vaporOut, waterVaporOut, freeWaterOut, vaporIn, publishedCondenserWatts}) {
            largest = Math.max(largest, Math.abs(term));
        }
        // One node's closure, so one tau of its own largest term rather than the whole-column sum.
        double limit = Math.max(Math.max(1.0, 1.0e-6 * largest), closureTolerance * largest);
        double magnitude = Math.abs(expected - publishedCondenserWatts);
        String detail = String.format(Locale.ROOT,
                "fresh condenser-node closure %.6g W vs published %.6g W; water vapor out=%.6g W, free water out=%.6g W",
                expected, publishedCondenserWatts, waterVaporOut, freeWaterOut);
        return Double.isFinite(magnitude) && magnitude <= limit
                ? V3AcceptanceAudit.Check.pass("CONDENSER_ENERGY_BALANCE", magnitude, limit, detail)
                : V3AcceptanceAudit.Check.fail("CONDENSER_ENERGY_BALANCE",
                        Double.isFinite(magnitude) ? magnitude : Double.MAX_VALUE, limit, detail);
    }

    /** Total hydrocarbon phase enthalpy rate of one node from a direct property call on the candidate flows. */
    private static double hydrocarbonPhaseEnergyWatts(
            V3ColumnProblem problem, V3ThermoModel thermo, V3DryMeshState state, int node, boolean liquid,
            V3ThermoWorkspace workspace) {
        V3ActiveComponentBasis active = problem.activeComponentBasis();
        double[] composition = new double[active.publicBasis().componentCount()];
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            double flow = liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
            composition[active.publicIndex(component)] = flow;
            total += flow;
        }
        if (!(total > 0.0) || !Double.isFinite(total)) return 0.0;
        for (int component = 0; component < composition.length; component++) composition[component] /= total;
        return total * thermo.molarEnthalpy(state.temperatureKelvin(node), problem.nodePressurePascal(node),
                composition, liquid ? V3Phase.LIQUID : V3Phase.VAPOR, workspace);
    }

    /** Advisory only: a cooled tray that condenses nearly all of its arriving vapor is at its physical cap. */
    private List<String> withCooledTrayAdvisory(List<String> advisoryEvidence, V3DryMeshState state) {
        int cappedTray = 0;
        double smallestRatio = Double.MAX_VALUE;
        for (int tray = 1; tray <= problem.topology().trayCount(); tray++) {
            if (problem.stageHeatWatts(tray) >= 0.0) continue;
            double entering = hydrocarbonVaporTotal(state, tray + 1);
            if (!(entering > 0.0)) continue;
            double ratio = hydrocarbonVaporTotal(state, tray) / entering;
            if (ratio < smallestRatio) {
                smallestRatio = ratio;
                cappedTray = tray;
            }
        }
        if (cappedTray == 0 || advisoryEvidence.size() >= 16) return advisoryEvidence;
        List<String> evidence = new ArrayList<>(advisoryEvidence);
        evidence.add(String.format(Locale.ROOT, smallestRatio < 1.0e-3
                        ? "cooled tray %d is condensation-capped: vapor leaving/entering %.4g"
                        : "smallest cooled-tray vapor leaving/entering ratio: tray %d at %.4g",
                cappedTray, smallestRatio));
        return List.copyOf(evidence);
    }

    private double refluxRatio() {
        return problem.input().specifications().stream()
                .filter(V3ColumnSpecification.OrganicRefluxRatio.class::isInstance)
                .map(V3ColumnSpecification.OrganicRefluxRatio.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V3 acceptance audit requires a reflux specification"))
                .ratio();
    }

    private V3AcceptanceAudit.Check waterProfile(V3DryMeshState state) {
        V3ColumnTopology topology = problem.topology();
        double[] expected = new double[topology.nodeCount()];
        double cumulative = 0.0;
        for (int node = topology.reboilerNode(); node >= 1; node--) {
            for (V3SteamFeedSpec feed : problem.input().steamFeeds()) {
                if (feed.stageNumber() == node) cumulative += feed.molarFlowMolPerSecond();
            }
            expected[node] = cumulative;
        }
        double maximum = 0.0;
        for (int node = 1; node <= topology.reboilerNode(); node++) {
            maximum = Math.max(maximum, relativeDifference(expected[node], problem.waterVaporFlowMolPerSecond(node)));
        }
        double waterAtCondenser = expected[1];
        IndependentWaterSplit split = independentCondenserWaterSplit(state);
        double waterSlip = split.vaporFlowMolPerSecond();
        double freeWater = split.freeWaterFlowMolPerSecond();
        maximum = Math.max(maximum, relativeDifference(waterAtCondenser, waterSlip + freeWater));
        return maximum <= 1.0e-12
                ? V3AcceptanceAudit.Check.pass("WATER_PROFILE", maximum, 1.0e-12,
                        "known free-water profile and overhead split recomputed from authored steam feeds")
                : V3AcceptanceAudit.Check.fail("WATER_PROFILE", maximum, 1.0e-12,
                        "known free-water profile or overhead split differs from authored steam feeds");
    }

    /**
     * The water dew point, read against the regime each node actually claims.
     *
     * <p>A <em>dry</em> node claims its vapour is not saturated, so its saturation ratio must not exceed one.
     * A <em>wet</em> tray claims the opposite — that its vapour sits exactly on the saturation line and the
     * surplus water has left as an aqueous liquid — so its ratio must equal one to the convergence closure
     * <em>and</em> its free water must be strictly positive. Both are reported on one scale: a dry node's
     * value is its ratio against a limit of one, a wet tray's is its deviation from one divided by the same
     * closure, so a value above one is a violation either way.</p>
     *
     * <p>The two regimes are ranked separately even though they share a scale, because they have different
     * severities: an inconsistent <em>wet</em> tray is a rejection, a supersaturated <em>dry</em> stage is only
     * a warning. Folding both into one worst node let a large dry ratio outrank a failing wet tray and turn the
     * whole check into that dry tray's warning, losing the wet-set failure entirely.</p>
     */
    V3AcceptanceAudit.Check waterDewPoint(V3DryMeshState state) {
        V3ColumnTopology topology = problem.topology();
        double maximum = 0.0;
        int emptyWetTray = -1;
        double worstWet = 0.0;
        int worstWetNode = -1;
        double worstWetPartialPressure = 0.0;
        double worstDry = 0.0;
        int worstDryNode = -1;
        double worstDryPartialPressure = 0.0;
        boolean dryValid = true;
        for (int node = 1; node <= topology.reboilerNode(); node++) {
            double water = problem.waterVaporFlow(state, node);
            if (water == 0.0 || state.temperatureKelvin(node) >= 640.0) continue;
            boolean wet = problem.isWetTray(node);
            if (wet && !(state.freeWaterFlow(node) > 0.0)) emptyWetTray = node;
            double value;
            double partialPressure = 0.0;
            try {
                double hydrocarbon = hydrocarbonVaporTotal(state, node);
                partialPressure = problem.nodePressurePascal(node) * water / (hydrocarbon + water);
                double ratio = partialPressure / V3WaterProperties.saturationPressurePascal(state.temperatureKelvin(node));
                value = wet ? Math.abs(ratio - 1.0) / closureLimit() : ratio;
                if (value > maximum) maximum = value;
            } catch (IllegalArgumentException invalidTemperature) {
                // A temperature outside the water correlation is as inconsistent as an unsatisfied ratio.
                value = Double.MAX_VALUE;
                maximum = Double.MAX_VALUE;
            }
            // A non-finite value cannot be ranked, so it enters as the largest violation there is.
            double rank = Double.isFinite(value) ? value : Double.MAX_VALUE;
            if (wet) {
                if (rank > 1.0 && rank > worstWet) {
                    worstWet = rank;
                    worstWetNode = node;
                    worstWetPartialPressure = partialPressure;
                }
            } else {
                dryValid &= rank <= 1.0;
                if (rank > worstDry) {
                    worstDry = rank;
                    worstDryNode = node;
                    worstDryPartialPressure = partialPressure;
                }
            }
        }
        if (emptyWetTray >= 0) {
            return V3AcceptanceAudit.Check.fail("WATER_DEW_POINT", maximum, 1.0,
                    "tray " + emptyWetTray + " is in the free-water set but sheds no free water");
        }
        // A wet tray that is not on the saturation line is a claim the state does not support, whatever any dry
        // stage reports; its own normalized error is what the check publishes.
        if (worstWetNode >= 0) {
            return V3AcceptanceAudit.Check.fail("WATER_DEW_POINT", worstWet, 1.0,
                    waterDewPointDetail(state, worstWetNode, worstWetPartialPressure));
        }
        if (dryValid) {
            return V3AcceptanceAudit.Check.pass("WATER_DEW_POINT", maximum, 1.0,
                    problem.hasWetTrays()
                            ? "every dry stage is above the free-water dew point and every wet tray sits on it"
                            : "all water-bearing stages remain above the free-water dew point");
        }
        // A dry stage below its water dew point is an operating condition the column can be in, not a modelling
        // failure: the steam-laden vapour is supersaturated there and water will condense on the tray. The column
        // is published, and the condition is reported as a warning (see withDewPointAdvisory).
        return V3AcceptanceAudit.Check.pass("WATER_DEW_POINT", worstDry, 1.0,
                "warning: " + waterDewPointDetail(state, worstDryNode, worstDryPartialPressure));
    }

    /** Names the stage, its temperature and the water dew point it sits below, in the units the operator authors. */
    private String waterDewPointDetail(V3DryMeshState state, int node, double partialPressurePascal) {
        if (node < 0) return "water would condense on a stage that carries no free-water phase";
        String stage = node == problem.topology().reboilerNode() ? "the bottom stage" : "tray " + node;
        if (problem.isWetTray(node)) {
            return String.format(Locale.ROOT,
                    "%s carries free water but its vapor is not on the water saturation line", stage);
        }
        String dewPoint;
        try {
            dewPoint = String.format(Locale.ROOT, "%.1f C",
                    V3WaterProperties.saturationTemperatureKelvin(partialPressurePascal) - 273.15);
        } catch (IllegalArgumentException outOfRange) {
            dewPoint = "its water dew point";
        }
        return String.format(Locale.ROOT,
                "%s at %.1f C is below the water dew point %s (steam partial pressure %.1f kPa) without a free-water "
                        + "phase; the free-water tray set did not admit it",
                stage, state.temperatureKelvin(node) - 273.15, dewPoint, partialPressurePascal / 1000.0);
    }

    /**
     * Closes the column's water balance node by node and at its boundary, independently of the evaluator.
     *
     * <p>Every node's water in — vapour from below, free water from above, authored steam — must equal its
     * water out, and the total steam fed must equal what the condenser publishes plus whatever free water
     * leaves with the bottoms (always zero: the sump is never wet). Both closures are relative to the total
     * steam. This is what would catch a wet tray whose free water was created or destroyed rather than
     * circulated, which no other check can see.</p>
     */
    private V3AcceptanceAudit.Check waterBalance(V3DryMeshState state) {
        V3ColumnTopology topology = problem.topology();
        double totalSteam = authoredWaterAtCondenser();
        if (!(totalSteam > 0.0)) {
            return V3AcceptanceAudit.Check.pass("WATER_BALANCE", 0.0, 1.0e-8, "no authored steam to balance");
        }
        double maximum = 0.0;
        for (int node = 1; node <= topology.reboilerNode(); node++) {
            double vaporIn = node < topology.reboilerNode() ? problem.waterVaporFlow(state, node + 1) : 0.0;
            double freeWaterIn = node >= 2 ? problem.freeWaterFlowMolPerSecond(state, node - 1) : 0.0;
            double in = vaporIn + freeWaterIn + problem.nodeSteamFeedMolPerSecond(node);
            double out = problem.waterVaporFlow(state, node) + problem.freeWaterFlowMolPerSecond(state, node);
            maximum = Math.max(maximum, Math.abs(in - out) / totalSteam);
        }
        V3ColumnProblem.WaterCondenserSplit split = problem.waterCondenserSplit(state);
        double bottomsFreeWater = problem.freeWaterFlowMolPerSecond(state, topology.reboilerNode());
        double leaving = split.vaporFlowMolPerSecond() + split.freeWaterFlowMolPerSecond() + bottomsFreeWater;
        maximum = Math.max(maximum, Math.abs(totalSteam - leaving) / totalSteam);
        String detail = String.format(Locale.ROOT,
                "steam in %.6g kmol/h; overhead vapor %.6g, decanted %.6g, bottoms free water %.6g kmol/h",
                totalSteam * 3.6, split.vaporFlowMolPerSecond() * 3.6, split.freeWaterFlowMolPerSecond() * 3.6,
                bottomsFreeWater * 3.6);
        return Double.isFinite(maximum) && maximum <= 1.0e-8
                ? V3AcceptanceAudit.Check.pass("WATER_BALANCE", maximum, 1.0e-8, detail)
                : V3AcceptanceAudit.Check.fail("WATER_BALANCE",
                        Double.isFinite(maximum) ? maximum : Double.MAX_VALUE, 1.0e-8, detail);
    }

    /** Advisory only: which trays carry a free-water phase and how much water they circulate. */
    /** Publishes a dry stage below its water dew point as a warning that names the tray; it never rejects the column. */
    private List<String> withDewPointAdvisory(List<String> advisoryEvidence, List<V3AcceptanceAudit.Check> checks) {
        if (advisoryEvidence.size() >= 16) return advisoryEvidence;
        for (V3AcceptanceAudit.Check check : checks) {
            if (!check.family().equals("WATER_DEW_POINT") || !check.passed() || !check.detail().startsWith("warning: ")) continue;
            List<String> evidence = new ArrayList<>(advisoryEvidence);
            String warning = "Warning: " + check.detail().substring("warning: ".length());
            evidence.add(warning.length() <= 256 ? warning : warning.substring(0, 256));
            return List.copyOf(evidence);
        }
        return advisoryEvidence;
    }

    private List<String> withWetTrayAdvisory(List<String> advisoryEvidence, V3DryMeshState state) {
        if (!problem.hasWetTrays() || advisoryEvidence.size() >= 16) return advisoryEvidence;
        List<String> evidence = new ArrayList<>(advisoryEvidence);
        evidence.add(problem.wetTraySet().event(state));
        return List.copyOf(evidence);
    }

    private V3AcceptanceAudit.Check freeWaterSplit(V3DryMeshState state) {
        V3ColumnProblem.WaterCondenserSplit split = problem.waterCondenserSplit(state);
        double freeWater = split.freeWaterFlowMolPerSecond();
        double total = problem.waterVaporFlowMolPerSecond(1);
        double fraction = total > 0.0 ? freeWater / total : 0.0;
        if (freeWater > 0.0 && Double.isFinite(freeWater)) {
            return V3AcceptanceAudit.Check.pass("FREE_WATER_SPLIT", fraction, 0.0,
                    "saturated condenser drum decants a positive free-water boot");
        }
        if (Double.isFinite(freeWater) && Double.isFinite(split.vaporFlowMolPerSecond())
                && split.vaporFlowMolPerSecond() >= total) {
            return V3AcceptanceAudit.Check.pass("FREE_WATER_SPLIT", 0.0, 0.0,
                    "available steam is insufficient to saturate the overhead; all water remains in mixed vapor");
        }
        return V3AcceptanceAudit.Check.fail("FREE_WATER_SPLIT", !Double.isFinite(freeWater) ? Double.MAX_VALUE
                : Math.max(0.0, -freeWater) / Math.max(1.0, total), 0.0,
                "condenser water split is not a finite, nonnegative vapor/free-water allocation");
    }

    private V3AcceptanceAudit.Check sideDrawSplit(V3DryMeshState state) {
        double maximum = 0.0;
        boolean valid = true;
        for (V3SideDrawSpec draw : problem.input().sideDraws()) {
            try {
                V3SideDraws.Withdrawal withdrawal = V3SideDraws.withdrawal(
                        state, draw.trayNumber(), draw.molarFlowMolPerSecond());
                valid &= withdrawal.liquidTotalMolPerSecond() > draw.molarFlowMolPerSecond();
                maximum = Math.max(maximum, withdrawal.fraction());
            } catch (IllegalArgumentException invalidSplit) {
                valid = false;
                maximum = Double.MAX_VALUE;
            }
        }
        return valid ? V3AcceptanceAudit.Check.pass("SIDE_DRAW_SPLIT", maximum, 1.0, "all side draws leave positive liquid downflow")
                : V3AcceptanceAudit.Check.fail("SIDE_DRAW_SPLIT", maximum, 1.0, "side draw exhausts the tray liquid; positive downflow required");
    }

    private V3AcceptanceAudit.Check finitenessAndTopology(V3DryMeshState state) {
        boolean valid = true;
        for (int node = 0; node < state.nodeCount(); node++) {
            valid &= Double.isFinite(state.temperatureKelvin(node)) && state.temperatureKelvin(node) > 0.0;
            for (int component = 0; component < state.componentCount(); component++) {
                // Absent is absent whether the support removed one phase of the point or the whole point:
                // the flow must be an exact zero, never a small positive number standing in for one.
                valid &= problem.hasVaporUnknown(node, component)
                        ? Double.isFinite(state.vaporFlow(node, component)) && state.vaporFlow(node, component) > 0.0
                        : state.vaporFlow(node, component) == 0.0;
                valid &= problem.hasLiquidUnknown(node, component)
                        ? Double.isFinite(state.liquidFlow(node, component)) && state.liquidFlow(node, component) > 0.0
                        : state.liquidFlow(node, component) == 0.0;
            }
        }
        double value = valid ? 0.0 : 1.0;
        return valid ? V3AcceptanceAudit.Check.pass("FINITE_TOPOLOGY", value, 0.0, "all active flows and topology phases are finite")
                : V3AcceptanceAudit.Check.fail("FINITE_TOPOLOGY", value, 0.0, "candidate violates a finite flow or absent-phase invariant");
    }

    /** Recomputes the carried sink edges from the candidate, never from a solver-cached defect or a new mask. */
    private V3AcceptanceAudit.Check truncationMassDefect(V3DryMeshState state) {
        double totalFeed = problem.activeComponentBasis().totalFeedFlowMolPerSecond();
        double reflux = problem.input().specifications().stream()
                .filter(V3ColumnSpecification.OrganicRefluxRatio.class::isInstance)
                .map(V3ColumnSpecification.OrganicRefluxRatio.class::cast).findFirst().orElseThrow().ratio();
        double fraction = 0.0;
        for (V3TruncationSupport.SinkEdge edge : problem.truncationSupport().sinkEdges()) {
            double flow = switch (edge.kind()) {
                case VAPOR_TO_ABOVE -> state.vaporFlow(edge.sourceNode(), edge.component());
                case LIQUID_TO_BELOW -> (1.0 - problem.liquidWithdrawalFraction(state, edge.sourceNode()))
                        * state.liquidFlow(edge.sourceNode(), edge.component());
                case REFLUX_TO_TRAY_ONE -> reflux / (1.0 + reflux) * state.liquidFlow(edge.sourceNode(), edge.component());
            };
            fraction += flow / totalFeed;
        }
        // Two independent budgets: the authored mole-fraction cutoff, and the always-on relative flow floor
        // whose omitted material is bounded by one component floor per sink edge.
        double limit = TRUNCATION_DEFECT_BUDGET * problem.truncationSupport().cutoffMoleFraction()
                + problem.truncationSupport().floorDefectBoundFraction();
        return fraction >= 0.0 && fraction <= limit
                ? V3AcceptanceAudit.Check.pass("TRUNCATION_MASS_DEFECT", fraction, limit, "fresh sink-edge defect as a fraction of authored feed")
                : V3AcceptanceAudit.Check.fail("TRUNCATION_MASS_DEFECT", Double.isFinite(fraction) ? Math.max(0.0, fraction) : Double.MAX_VALUE,
                        limit, "sink-edge defect is negative or exceeds the stage-trace mass budget");
    }

    /**
     * Bounds what a one-phase point approximates away, recomputed from the candidate.
     *
     * <p>A one-phase point loses no mass: its material row conserves the component into the phase that is
     * present, so it has no sink edge and the {@code TRUNCATION_MASS_DEFECT} budget says nothing about it.
     * What it does assume is that the absent phase would carry nothing worth solving for, and the quantity
     * that makes that true or false is the flow the equilibrium row would have given it,
     * {@code v* = K_c (V/L) l} for a liquid-only point and {@code l* = v L / (K_c V)} for a vapour-only one.
     * The reinsertion rule restores a phase once that reaches {@link V3TruncationSupport#FLOOR_REINSERTION_FACTOR}
     * floors, so a published state cannot exceed it by more than the roundoff slack.</p>
     */
    private V3AcceptanceAudit.Check phaseTruncationDefect(V3DryMeshState state, V3ThermoWorkspace workspace) {
        V3TruncationSupport support = problem.truncationSupport();
        double limit = support.phaseDefectBoundFraction();
        double worst = 0.0;
        V3StageEquilibriumRatios equilibrium = V3StageEquilibriumRatios.of(problem, thermo, state, workspace);
        for (int node = 0; node < state.nodeCount(); node++) {
            V3StageEquilibriumRatios.Node ratios = equilibrium.node(node);
            for (int component = 0; component < state.componentCount(); component++) {
                V3TruncationSupport.PointPhases point = support.pointPhases(node, component);
                if (!point.isOnePhase()) continue;
                if (ratios == null) {
                    return V3AcceptanceAudit.Check.fail("PHASE_TRUNCATION_DEFECT", Double.MAX_VALUE, limit,
                            "candidate has no evaluable equilibrium ratio for a one-phase stage point");
                }
                double implied = point == V3TruncationSupport.PointPhases.LIQUID_ONLY
                        ? ratios.impliedVaporFlowMolPerSecond(component, state.liquidFlow(node, component))
                        : ratios.impliedLiquidFlowMolPerSecond(component, state.vaporFlow(node, component));
                double scale = problem.activeComponentBasis().flowScale(component);
                worst = Math.max(worst, scale > 0.0 ? implied / scale : Double.MAX_VALUE);
            }
        }
        return Double.isFinite(worst) && worst >= 0.0 && worst <= limit
                ? V3AcceptanceAudit.Check.pass("PHASE_TRUNCATION_DEFECT", worst, limit,
                        "largest equilibrium-implied absent-phase flow as a fraction of its component feed")
                : V3AcceptanceAudit.Check.fail("PHASE_TRUNCATION_DEFECT",
                        Double.isFinite(worst) ? Math.max(0.0, worst) : Double.MAX_VALUE, limit,
                        "a one-phase stage point implies more absent-phase flow than the reinsertion threshold");
    }

    private V3AcceptanceAudit.Check liquidCondenserPhase(V3DryMeshState state, V3ThermoWorkspace workspace) {
        int node = problem.topology().condenserNode();
        double[] liquid = new double[problem.input().componentBasis().componentCount()];
        for (int component = 0; component < state.componentCount(); component++) {
            liquid[problem.activeComponentBasis().publicIndex(component)] = state.liquidFlow(node, component);
        }
        V3FeedPhase phase = problem.hasSteamFeeds()
                ? independentCondenserFlash(state, liquid, liquid, liquid, workspace).phase()
                : thermo.flashTP(state.temperatureKelvin(node), problem.nodePressurePascal(node), liquid, workspace).phase();
        return phase == V3FeedPhase.LIQUID
                ? V3AcceptanceAudit.Check.pass("CONDENSER_PHASE", 0.0, 0.0,
                        "water-adjusted outlet TP flash confirms liquid only")
                : V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", 1.0, 0.0,
                        "water-adjusted outlet TP flash requires a vapor phase");
    }

    /** Independently flashes the solved combined outlets and compares their component phase split. */
    private V3AcceptanceAudit.Check twoPhaseCondenserSplit(V3DryMeshState state, V3ThermoWorkspace workspace) {
        int node = problem.topology().condenserNode();
        int publicComponents = problem.input().componentBasis().componentCount();
        double[] liquid = new double[publicComponents];
        double[] vapor = new double[publicComponents];
        double total = 0.0;
        double vaporTotal = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            int publicComponent = problem.activeComponentBasis().publicIndex(component);
            liquid[publicComponent] = state.liquidFlow(node, component);
            vapor[publicComponent] = state.vaporFlow(node, component);
            total += liquid[publicComponent] + vapor[publicComponent];
            vaporTotal += vapor[publicComponent];
        }
        if (!(total > 0.0) || !Double.isFinite(total) || !Double.isFinite(vaporTotal)) {
            return V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", 1.0, closureLimit(),
                    "combined condenser outlet has no finite positive total flow");
        }
        double[] overall = new double[publicComponents];
        for (int component = 0; component < publicComponents; component++) {
            overall[component] = (liquid[component] + vapor[component]) / total;
        }
        IndependentCondenserFlash flash = independentCondenserFlash(state, overall, liquid, vapor, workspace);
        if (!flash.converged()) {
            return fallbackTwoPhaseCondenserPhase(state, overall, workspace);
        }
        if (flash.phase() != V3FeedPhase.TWO_PHASE) {
            return V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", 1.0, closureLimit(),
                    "combined outlet TP flash is " + flash.phase() + ", not two-phase");
        }
        double[] flashLiquid = flash.liquidComposition();
        double[] flashVapor = flash.vaporComposition();
        if (flashLiquid.length != publicComponents || flashVapor.length != publicComponents) {
            return V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", 1.0, closureLimit(),
                    "combined outlet TP flash has a different component basis");
        }
        double beta = flash.vaporFraction();
        double maximum = Math.abs(vaporTotal / total - beta);
        for (int component = 0; component < publicComponents; component++) {
            maximum = Math.max(maximum, Math.abs(liquid[component] / total - (1.0 - beta) * flashLiquid[component]));
            maximum = Math.max(maximum, Math.abs(vapor[component] / total - beta * flashVapor[component]));
        }
        String detail = "fresh water-adjusted scaled-K combined-outlet flash; beta=" + beta;
        return maximum <= closureLimit()
                ? V3AcceptanceAudit.Check.pass("CONDENSER_PHASE", maximum, closureLimit(), detail)
                : V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", maximum, closureLimit(), detail);
    }

    /**
     * The primary scaled-K split check can become ill-conditioned on a trace-heavy VLE state.
     * This fallback still independently flashes the combined outlet at the hydrocarbon partial
     * pressure; fresh component-equilibrium and material checks remain mandatory audit gates.
     */
    private V3AcceptanceAudit.Check fallbackTwoPhaseCondenserPhase(
            V3DryMeshState state, double[] overall, V3ThermoWorkspace workspace) {
        int condenser = problem.topology().condenserNode();
        IndependentWaterSplit water = independentCondenserWaterSplit(state);
        double hydrocarbon = hydrocarbonVaporTotal(state, condenser);
        double totalPressure = problem.nodePressurePascal(condenser);
        double hydrocarbonPressure;
        if (hydrocarbon > 0.0) {
            hydrocarbonPressure = totalPressure * hydrocarbon / (hydrocarbon + water.vaporFlowMolPerSecond());
        } else if (water.freeWaterFlowMolPerSecond() > 0.0) {
            hydrocarbonPressure = totalPressure - V3WaterProperties.saturationPressurePascal(state.temperatureKelvin(condenser));
        } else {
            hydrocarbonPressure = Double.NaN;
        }
        if (!Double.isFinite(hydrocarbonPressure) || hydrocarbonPressure <= 0.0) {
            return V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", 1.0, closureLimit(),
                    "water-adjusted fallback condenser pressure is not finite and positive");
        }
        V3FlashResult flash = thermo.flashTP(state.temperatureKelvin(condenser), hydrocarbonPressure, overall, workspace);
        return flash.phase() == V3FeedPhase.TWO_PHASE
                ? V3AcceptanceAudit.Check.pass("CONDENSER_PHASE", 0.0, closureLimit(),
                        "independent water-adjusted TP phase fallback; component split separately audited")
                : V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", 1.0, closureLimit(),
                        "water-adjusted fallback TP flash is " + flash.phase() + ", not two-phase");
    }

    /**
     * Independently re-derives the authored water allocation for WATER_PROFILE. This deliberately
     * does not call the problem split helper: that helper is part of the numerical formulation
     * under audit.
     */
    private IndependentWaterSplit independentCondenserWaterSplit(V3DryMeshState state) {
        return independentCondenserWaterSplit(problem, state);
    }

    private static IndependentWaterSplit independentCondenserWaterSplit(V3ColumnProblem problem, V3DryMeshState state) {
        double arrivingWater = authoredWaterAtCondenser(problem);
        int condenser = problem.topology().condenserNode();
        return switch (problem.waterCondenserRegime()) {
            case NONE -> new IndependentWaterSplit(0.0, 0.0);
            case ALL_VAPOR -> new IndependentWaterSplit(arrivingWater, 0.0);
            case FREE_WATER -> {
                double vaporWater = problem.topology().condenserPhaseBranch() == V3CondenserPhaseBranch.TWO_PHASE
                        ? Math.min(arrivingWater, independentWaterSlipCoefficient(problem)
                                * hydrocarbonVaporTotal(state, condenser)) : 0.0;
                yield new IndependentWaterSplit(vaporWater, arrivingWater - vaporWater);
            }
        };
    }

    /**
     * Fresh hydrocarbon-only flash using the solver's published wet-equilibrium contract:
     * K_hc is evaluated from full-pressure hydrocarbon fugacities then divided by 1-y(H2O).
     * This deliberately does not use a solver residual, cached flash, or the problem split helper.
     */
    private IndependentCondenserFlash independentCondenserFlash(
            V3DryMeshState state, double[] overall, double[] liquidGuess, double[] vaporGuess,
            V3ThermoWorkspace workspace) {
        int condenser = problem.topology().condenserNode();
        if (!problem.hasSteamFeeds()) {
            V3FlashResult dryFlash = thermo.flashTP(state.temperatureKelvin(condenser),
                    problem.nodePressurePascal(condenser), overall, workspace);
            return new IndependentCondenserFlash(dryFlash.phase(), dryFlash.vaporFraction(), dryFlash.liquidComposition(),
                    dryFlash.vaporComposition(), true);
        }
        double totalHydrocarbon = hydrocarbonTotal(state, condenser);
        double arrivingWater = authoredWaterAtCondenser();
        if (!(totalHydrocarbon > 0.0) || !Double.isFinite(totalHydrocarbon)
                || !(arrivingWater > 0.0) || !Double.isFinite(arrivingWater)) {
            return IndependentCondenserFlash.unavailable(overall.length);
        }
        if (problem.waterCondenserRegime() == V3WaterCondenserRegime.FREE_WATER) {
            double saturationFraction = V3WaterProperties.saturationPressurePascal(state.temperatureKelvin(condenser))
                    / problem.nodePressurePascal(condenser);
            IndependentCondenserFlash saturated = flashAtWaterMoleFraction(
                    state, overall, liquidGuess, vaporGuess, saturationFraction, workspace);
            if (!saturated.converged()) return saturated;
            double requiredVaporWater = saturationFraction / (1.0 - saturationFraction)
                    * phaseFraction(saturated) * totalHydrocarbon;
            if (arrivingWater >= requiredVaporWater) return saturated;
            return waterLimitedFlash(state, overall, liquidGuess, vaporGuess, arrivingWater, totalHydrocarbon,
                    saturationFraction, saturated, workspace);
        }
        return waterLimitedFlash(state, overall, liquidGuess, vaporGuess, arrivingWater, totalHydrocarbon,
                1.0, null, workspace);
    }

    private IndependentCondenserFlash flashAtWaterMoleFraction(
            V3DryMeshState state, double[] overall, double[] liquidGuess, double[] vaporGuess,
            double waterMoleFraction, V3ThermoWorkspace workspace) {
        overall = normalized(overall);
        if (overall == null) return IndependentCondenserFlash.unavailable(liquidGuess.length);
        int condenser = problem.topology().condenserNode();
        double totalPressure = problem.nodePressurePascal(condenser);
        if (!Double.isFinite(waterMoleFraction) || waterMoleFraction < 0.0 || waterMoleFraction >= 1.0) {
            return IndependentCondenserFlash.unavailable(overall.length);
        }
        double vaporScale = 1.0 - waterMoleFraction;
        double[] liquid = normalized(liquidGuess);
        double[] vapor = normalized(vaporGuess);
        if (liquid == null || vapor == null) return IndependentCondenserFlash.unavailable(overall.length);
        for (int iteration = 0; iteration < 256; iteration++) {
            V3FugacityResult liquidFugacity = thermo.fugacity(state.temperatureKelvin(condenser), totalPressure,
                    liquid, V3Phase.LIQUID, workspace);
            V3FugacityResult vaporFugacity = thermo.fugacity(state.temperatureKelvin(condenser), totalPressure,
                    vapor, V3Phase.VAPOR, workspace);
            double[] kValues = new double[overall.length];
            for (int component = 0; component < kValues.length; component++) {
                kValues[component] = Math.exp(liquidFugacity.logFugacityCoefficient(component)
                        - vaporFugacity.logFugacityCoefficient(component)) / vaporScale;
                if (!Double.isFinite(kValues[component]) || kValues[component] <= 0.0) {
                    return IndependentCondenserFlash.unavailable(overall.length);
                }
            }
            double fAtZero = rachfordRice(overall, kValues, 0.0);
            double fAtOne = rachfordRice(overall, kValues, 1.0);
            if (!Double.isFinite(fAtZero) || !Double.isFinite(fAtOne)) {
                return IndependentCondenserFlash.unavailable(overall.length);
            }
            if (fAtZero <= 0.0) return new IndependentCondenserFlash(V3FeedPhase.LIQUID, 0.0, liquid, new double[0], true);
            if (fAtOne >= 0.0) return new IndependentCondenserFlash(V3FeedPhase.VAPOR, 1.0, new double[0], vapor, true);
            double lower = 0.0;
            double upper = 1.0;
            for (int bisection = 0; bisection < 80; bisection++) {
                double middle = (lower + upper) * 0.5;
                if (rachfordRice(overall, kValues, middle) > 0.0) lower = middle;
                else upper = middle;
            }
            double vaporFraction = (lower + upper) * 0.5;
            double[] nextLiquid = new double[overall.length];
            double[] nextVapor = new double[overall.length];
            for (int component = 0; component < overall.length; component++) {
                nextLiquid[component] = overall[component] / (1.0 + vaporFraction * (kValues[component] - 1.0));
                nextVapor[component] = kValues[component] * nextLiquid[component];
            }
            // Full substitution can oscillate for a heavy hydrocarbon mixture diluted by a large
            // fixed water vapor fraction. Damping remains independent of the MESH residual while
            // converging the same full-pressure scaled-K fixed point.
            nextLiquid = normalized(blend(liquid, nextLiquid, 0.35));
            nextVapor = normalized(blend(vapor, nextVapor, 0.35));
            if (nextLiquid == null || nextVapor == null) return IndependentCondenserFlash.unavailable(overall.length);
            double change = 0.0;
            for (int component = 0; component < overall.length; component++) {
                // A component below the published phase-split tolerance cannot affect the
                // independent outlet check, but its normalized trace composition can oscillate.
                if (overall[component] <= CONDENSER_PHASE_SPLIT_LIMIT) continue;
                change = Math.max(change, Math.abs(nextLiquid[component] - liquid[component]));
                change = Math.max(change, Math.abs(nextVapor[component] - vapor[component]));
            }
            liquid = nextLiquid;
            vapor = nextVapor;
            if (change <= CONDENSER_PHASE_SPLIT_LIMIT) {
                return new IndependentCondenserFlash(V3FeedPhase.TWO_PHASE, vaporFraction, liquid, vapor, true);
            }
        }
        return IndependentCondenserFlash.unavailable(overall.length);
    }

    /** Solves the water-limited alternative, in which every mole of fed steam leaves with the overhead vapor. */
    private IndependentCondenserFlash waterLimitedFlash(
            V3DryMeshState state, double[] overall, double[] liquidGuess, double[] vaporGuess,
            double arrivingWater, double totalHydrocarbon, double saturationLimit,
            IndependentCondenserFlash saturated, V3ThermoWorkspace workspace) {
        int condenser = problem.topology().condenserNode();
        double beta = hydrocarbonVaporTotal(state, condenser) / totalHydrocarbon;
        if (!Double.isFinite(beta) || beta <= 0.0) beta = 0.05;
        double waterMoleFraction = arrivingWater / (arrivingWater + beta * totalHydrocarbon);
        if (waterMoleFraction >= saturationLimit && saturated != null) return saturated;
        for (int iteration = 0; iteration < 64; iteration++) {
            IndependentCondenserFlash flash = flashAtWaterMoleFraction(
                    state, overall, liquidGuess, vaporGuess, waterMoleFraction, workspace);
            if (!flash.converged()) return flash;
            beta = phaseFraction(flash);
            if (beta <= 1.0e-12) return flash;
            double nextWaterFraction = arrivingWater / (arrivingWater + beta * totalHydrocarbon);
            if (nextWaterFraction >= saturationLimit && saturated != null) return saturated;
            if (Math.abs(nextWaterFraction - waterMoleFraction) <= 1.0e-11) return flash;
            waterMoleFraction = 0.5 * (waterMoleFraction + nextWaterFraction);
        }
        return IndependentCondenserFlash.unavailable(overall.length);
    }

    private static double phaseFraction(IndependentCondenserFlash flash) {
        return switch (flash.phase()) {
            case LIQUID -> 0.0;
            case TWO_PHASE -> flash.vaporFraction();
            case VAPOR -> 1.0;
        };
    }

    private static double rachfordRice(double[] overall, double[] kValues, double vaporFraction) {
        double value = 0.0;
        for (int component = 0; component < overall.length; component++) {
            double shift = kValues[component] - 1.0;
            value += overall[component] * shift / (1.0 + vaporFraction * shift);
        }
        return value;
    }

    private static double[] normalized(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        if (!Double.isFinite(total) || total <= 0.0) return null;
        double[] normalized = new double[values.length];
        for (int index = 0; index < values.length; index++) normalized[index] = values[index] / total;
        return normalized;
    }

    private static double[] blend(double[] current, double[] target, double fraction) {
        double[] result = new double[current.length];
        for (int index = 0; index < result.length; index++) {
            result[index] = current[index] + fraction * (target[index] - current[index]);
        }
        return result;
    }

    private double independentWaterSlipCoefficient() {
        return independentWaterSlipCoefficient(problem);
    }

    private static double independentWaterSlipCoefficient(V3ColumnProblem problem) {
        int condenser = problem.topology().condenserNode();
        double temperature = condenserTemperatureKelvin(problem);
        double waterFraction = V3WaterProperties.saturationPressurePascal(temperature)
                / problem.nodePressurePascal(condenser);
        return waterFraction / (1.0 - waterFraction);
    }

    private double condenserTemperatureKelvin() {
        return condenserTemperatureKelvin(problem);
    }

    private static double condenserTemperatureKelvin(V3ColumnProblem problem) {
        return problem.input().specifications().stream()
                .filter(V3ColumnSpecification.CondenserOutletTemperature.class::isInstance)
                .map(V3ColumnSpecification.CondenserOutletTemperature.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "V3 acceptance audit requires a condenser-temperature specification")).kelvin();
    }

    private double authoredWaterAtCondenser() {
        return authoredWaterAtCondenser(problem);
    }

    private static double authoredWaterAtCondenser(V3ColumnProblem problem) {
        return problem.input().steamFeeds().stream().mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum();
    }

    private static double hydrocarbonVaporTotal(V3DryMeshState state, int node) {
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) total += state.vaporFlow(node, component);
        return total;
    }

    private static double hydrocarbonTotal(V3DryMeshState state, int node) {
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) {
            total += state.liquidFlow(node, component) + state.vaporFlow(node, component);
        }
        return total;
    }

    private static double relativeDifference(double left, double right) {
        return Math.abs(left - right) / Math.max(1.0, Math.max(Math.abs(left), Math.abs(right)));
    }

    private record IndependentWaterSplit(double vaporFlowMolPerSecond, double freeWaterFlowMolPerSecond) {}

    private record IndependentCondenserFlash(
            V3FeedPhase phase, double vaporFraction, double[] liquidComposition, double[] vaporComposition, boolean converged) {
        private static IndependentCondenserFlash unavailable(int componentCount) {
            return new IndependentCondenserFlash(V3FeedPhase.LIQUID, 0.0, new double[componentCount],
                    new double[componentCount], false);
        }
    }

    private static V3AcceptanceAudit.Check maximumFamily(
            V3MeshResidual residual, V3DegreeOfFreedomLedger.EquationFamily family, String auditFamily, double limit) {
        double maximum = 0.0;
        boolean found = false;
        for (V3MeshResidual.Row row : residual.rows()) {
            if (row.equation().family() != family) continue;
            found = true;
            maximum = Math.max(maximum, Math.abs(row.scaledValue()));
        }
        if (!found) return V3AcceptanceAudit.Check.fail(auditFamily, 1.0, limit, "required dry acceptance family is absent");
        return maximum <= limit ? V3AcceptanceAudit.Check.pass(auditFamily, maximum, limit, "fresh residual recomputation")
                : V3AcceptanceAudit.Check.fail(auditFamily, maximum, limit, "fresh residual recomputation exceeded its limit");
    }
}

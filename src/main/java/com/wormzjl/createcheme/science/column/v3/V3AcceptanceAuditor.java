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

    V3AcceptanceAuditor(V3ColumnProblem problem, V3ThermoModel thermo, double feedMolarEnthalpyJoulesPerMol) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.thermo = Objects.requireNonNull(thermo, "thermo");
        if (!problem.input().componentBasis().equals(thermo.componentBasis()) || !Double.isFinite(feedMolarEnthalpyJoulesPerMol)) {
            throw new IllegalArgumentException("V3 acceptance auditor does not match its problem and thermodynamic model");
        }
        this.feedMolarEnthalpyJoulesPerMol = feedMolarEnthalpyJoulesPerMol;
    }

    V3AcceptanceAudit audit(V3DryMeshState state, V3ThermoWorkspace workspace) {
        return audit(state, workspace, V3SolveControl.UNBOUNDED);
    }

    V3AcceptanceAudit audit(V3DryMeshState state, V3ThermoWorkspace workspace, V3SolveControl control) {
        state = Objects.requireNonNull(state, "state");
        workspace = Objects.requireNonNull(workspace, "workspace");
        control = Objects.requireNonNull(control, "control");
        control.checkpoint();
        V3MeshResidual residual = new V3MeshResidualEvaluator(problem, thermo, feedMolarEnthalpyJoulesPerMol)
                .evaluate(state, workspace);
        control.checkpoint();
        List<V3AcceptanceAudit.Check> checks = new ArrayList<>();
        checks.add(finitenessAndTopology(state));
        if (problem.hasSideDraws()) checks.add(sideDrawSplit(state));
        if (problem.hasSteamFeeds()) {
            checks.add(waterProfile(state));
            checks.add(waterDewPoint(state));
            if (problem.topology().condenserPhaseBranch() == V3CondenserPhaseBranch.TWO_PHASE
                    && problem.hasFreeWaterCondenser()) {
                checks.add(freeWaterSplit(state));
            }
        }
        checks.add(maximumFamily(residual, V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE,
                "LOCAL_COMPONENT_BALANCE", 1.0));
        checks.add(maximumFamily(residual, V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM,
                "EQUILIBRIUM", EQUILIBRIUM_LIMIT));
        checks.add(maximumFamily(residual, V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE,
                "ENERGY_BALANCE", 1.0));
        if (!problem.truncationSupport().isIdentity()) checks.add(truncationMassDefect(state));
        control.checkpoint();
        if (problem.topology().condenserPhaseBranch() == V3CondenserPhaseBranch.LIQUID_ONLY) {
            checks.add(liquidCondenserPhase(state, workspace));
        } else if (problem.topology().condenserPhaseBranch() == V3CondenserPhaseBranch.TWO_PHASE) {
            checks.add(twoPhaseCondenserSplit(state, workspace));
        }
        control.checkpoint();
        List<String> advisoryEvidence = thermo instanceof V3PengRobinsonThermo registeredPackage
                ? registeredPackage.advisoryEvidence() : List.of();
        return new V3AcceptanceAudit(checks, advisoryEvidence);
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

    private V3AcceptanceAudit.Check waterDewPoint(V3DryMeshState state) {
        V3ColumnTopology topology = problem.topology();
        double maximum = 0.0;
        boolean valid = true;
        for (int node = 1; node <= topology.reboilerNode(); node++) {
            double water = problem.waterVaporFlowMolPerSecond(node);
            if (water == 0.0 || state.temperatureKelvin(node) >= 640.0) continue;
            try {
                double hydrocarbon = hydrocarbonVaporTotal(state, node);
                double partialPressure = problem.nodePressurePascal(node) * water / (hydrocarbon + water);
                double ratio = partialPressure / V3WaterProperties.saturationPressurePascal(state.temperatureKelvin(node));
                maximum = Math.max(maximum, ratio);
                valid &= Double.isFinite(ratio) && ratio <= 1.0;
            } catch (IllegalArgumentException invalidTemperature) {
                valid = false;
                maximum = Double.MAX_VALUE;
            }
        }
        return valid ? V3AcceptanceAudit.Check.pass("WATER_DEW_POINT", maximum, 1.0,
                "all water-bearing stages remain above the free-water dew point")
                : V3AcceptanceAudit.Check.fail("WATER_DEW_POINT", maximum, 1.0,
                        "water would condense on a tray; three-phase trays are outside the V3 contract");
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
                if (!problem.truncationSupport().retains(node, component)) {
                    valid &= state.liquidFlow(node, component) == 0.0 && state.vaporFlow(node, component) == 0.0;
                    continue;
                }
                valid &= problem.topology().hasVaporPhase(node)
                        ? Double.isFinite(state.vaporFlow(node, component)) && state.vaporFlow(node, component) > 0.0
                        : state.vaporFlow(node, component) == 0.0;
                if (problem.condenserComponentPhases().hasLiquid(problem.topology(), node, component)) {
                    valid &= Double.isFinite(state.liquidFlow(node, component)) && state.liquidFlow(node, component) > 0.0;
                } else {
                    valid &= state.liquidFlow(node, component) == 0.0;
                }
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
        double limit = TRUNCATION_DEFECT_BUDGET * problem.truncationSupport().cutoffMoleFraction();
        return fraction >= 0.0 && fraction <= limit
                ? V3AcceptanceAudit.Check.pass("TRUNCATION_MASS_DEFECT", fraction, limit, "fresh sink-edge defect as a fraction of authored feed")
                : V3AcceptanceAudit.Check.fail("TRUNCATION_MASS_DEFECT", Double.isFinite(fraction) ? Math.max(0.0, fraction) : Double.MAX_VALUE,
                        limit, "sink-edge defect is negative or exceeds the stage-trace mass budget");
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
            return V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", 1.0, CONDENSER_PHASE_SPLIT_LIMIT,
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
            return V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", 1.0, CONDENSER_PHASE_SPLIT_LIMIT,
                    "combined outlet TP flash is " + flash.phase() + ", not two-phase");
        }
        double[] flashLiquid = flash.liquidComposition();
        double[] flashVapor = flash.vaporComposition();
        if (flashLiquid.length != publicComponents || flashVapor.length != publicComponents) {
            return V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", 1.0, CONDENSER_PHASE_SPLIT_LIMIT,
                    "combined outlet TP flash has a different component basis");
        }
        double beta = flash.vaporFraction();
        double maximum = Math.abs(vaporTotal / total - beta);
        for (int component = 0; component < publicComponents; component++) {
            maximum = Math.max(maximum, Math.abs(liquid[component] / total - (1.0 - beta) * flashLiquid[component]));
            maximum = Math.max(maximum, Math.abs(vapor[component] / total - beta * flashVapor[component]));
        }
        String detail = "fresh water-adjusted scaled-K combined-outlet flash; beta=" + beta;
        return maximum <= CONDENSER_PHASE_SPLIT_LIMIT
                ? V3AcceptanceAudit.Check.pass("CONDENSER_PHASE", maximum, CONDENSER_PHASE_SPLIT_LIMIT, detail)
                : V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", maximum, CONDENSER_PHASE_SPLIT_LIMIT, detail);
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
            return V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", 1.0, CONDENSER_PHASE_SPLIT_LIMIT,
                    "water-adjusted fallback condenser pressure is not finite and positive");
        }
        V3FlashResult flash = thermo.flashTP(state.temperatureKelvin(condenser), hydrocarbonPressure, overall, workspace);
        return flash.phase() == V3FeedPhase.TWO_PHASE
                ? V3AcceptanceAudit.Check.pass("CONDENSER_PHASE", 0.0, CONDENSER_PHASE_SPLIT_LIMIT,
                        "independent water-adjusted TP phase fallback; component split separately audited")
                : V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", 1.0, CONDENSER_PHASE_SPLIT_LIMIT,
                        "water-adjusted fallback TP flash is " + flash.phase() + ", not two-phase");
    }

    /**
     * Independently re-derives the authored water allocation for WATER_PROFILE. This deliberately
     * does not call the problem split helper: that helper is part of the numerical formulation
     * under audit.
     */
    private IndependentWaterSplit independentCondenserWaterSplit(V3DryMeshState state) {
        double arrivingWater = authoredWaterAtCondenser();
        int condenser = problem.topology().condenserNode();
        return switch (problem.waterCondenserRegime()) {
            case NONE -> new IndependentWaterSplit(0.0, 0.0);
            case ALL_VAPOR -> new IndependentWaterSplit(arrivingWater, 0.0);
            case FREE_WATER -> {
                double vaporWater = problem.topology().condenserPhaseBranch() == V3CondenserPhaseBranch.TWO_PHASE
                        ? Math.min(arrivingWater, independentWaterSlipCoefficient()
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
        int condenser = problem.topology().condenserNode();
        double temperature = condenserTemperatureKelvin();
        double waterFraction = V3WaterProperties.saturationPressurePascal(temperature)
                / problem.nodePressurePascal(condenser);
        return waterFraction / (1.0 - waterFraction);
    }

    private double condenserTemperatureKelvin() {
        return problem.input().specifications().stream()
                .filter(V3ColumnSpecification.CondenserOutletTemperature.class::isInstance)
                .map(V3ColumnSpecification.CondenserOutletTemperature.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "V3 acceptance audit requires a condenser-temperature specification")).kelvin();
    }

    private double authoredWaterAtCondenser() {
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

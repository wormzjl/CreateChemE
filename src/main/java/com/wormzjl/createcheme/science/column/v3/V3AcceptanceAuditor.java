package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
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
        double[] feeds = V3SteamFeeds.nodeFeedFlows(problem.input(), topology);
        double[] expected = V3SteamFeeds.upwardVaporProfile(feeds, topology);
        double maximum = 0.0;
        for (int node = 1; node <= topology.reboilerNode(); node++) {
            maximum = Math.max(maximum, relativeDifference(expected[node], problem.waterVaporFlowMolPerSecond(node)));
        }
        double waterAtCondenser = expected[1];
        double waterSlip = waterOverheadSlip(state);
        double freeWater = freeWaterFlow(state);
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
        double freeWater = freeWaterFlow(state);
        double total = problem.waterVaporFlowMolPerSecond(1);
        double fraction = total > 0.0 ? freeWater / total : 0.0;
        return freeWater > 0.0 && Double.isFinite(freeWater)
                ? V3AcceptanceAudit.Check.pass("FREE_WATER_SPLIT", fraction, 0.0,
                        "saturated condenser drum decants a positive free-water boot")
                : V3AcceptanceAudit.Check.fail("FREE_WATER_SPLIT", fraction, 0.0,
                        "saturated condenser drum has no positive free-water boot");
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
        V3FlashResult flash = thermo.flashTP(state.temperatureKelvin(node), problem.nodePressurePascal(node),
                liquid, workspace);
        return flash.phase() == V3FeedPhase.LIQUID
                ? V3AcceptanceAudit.Check.pass("CONDENSER_PHASE", 0.0, 0.0, "outlet TP flash confirms liquid only")
                : V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", 1.0, 0.0, "outlet TP flash requires a vapor phase");
    }

    /** Independently flashes the solved combined outlets and compares their component phase split. */
    private V3AcceptanceAudit.Check twoPhaseCondenserSplit(V3DryMeshState state, V3ThermoWorkspace workspace) {
        if (problem.hasSteamFeeds()) return wetTwoPhaseCondenserSplit(state);
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
        V3FlashResult flash = thermo.flashTP(state.temperatureKelvin(node), problem.nodePressurePascal(node),
                overall, workspace);
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
        String detail = "fresh combined-outlet TP flash; beta=" + beta;
        return maximum <= CONDENSER_PHASE_SPLIT_LIMIT
                ? V3AcceptanceAudit.Check.pass("CONDENSER_PHASE", maximum, CONDENSER_PHASE_SPLIT_LIMIT, detail)
                : V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", maximum, CONDENSER_PHASE_SPLIT_LIMIT, detail);
    }

    /** The hydrocarbon TP flash is water-free by contract; equilibrium residuals independently audit the wet K shift. */
    private V3AcceptanceAudit.Check wetTwoPhaseCondenserSplit(V3DryMeshState state) {
        double slip = waterOverheadSlip(state);
        double expectedSlip = problem.hasAllVaporWaterCondenser() ? problem.waterVaporFlowMolPerSecond(1)
                : problem.waterVaporSlipCoefficient()
                        * hydrocarbonVaporTotal(state, problem.topology().condenserNode());
        double maximum = relativeDifference(slip, expectedSlip);
        return maximum <= CONDENSER_PHASE_SPLIT_LIMIT
                ? V3AcceptanceAudit.Check.pass("CONDENSER_PHASE", maximum, CONDENSER_PHASE_SPLIT_LIMIT,
                        problem.hasAllVaporWaterCondenser()
                                ? "condenser is too warm for a water boot; stripping steam exits as vapor"
                                : "saturated free-water drum uses the resolved water-slip coefficient")
                : V3AcceptanceAudit.Check.fail("CONDENSER_PHASE", maximum, CONDENSER_PHASE_SPLIT_LIMIT,
                        "water condenser split does not satisfy its resolved vapor regime");
    }

    private double waterOverheadSlip(V3DryMeshState state) {
        return switch (problem.waterCondenserRegime()) {
            case NONE -> 0.0;
            case ALL_VAPOR -> problem.waterVaporFlowMolPerSecond(1);
            case FREE_WATER -> problem.topology().condenserPhaseBranch() == V3CondenserPhaseBranch.TWO_PHASE
                    ? problem.waterVaporSlipCoefficient()
                            * hydrocarbonVaporTotal(state, problem.topology().condenserNode())
                    : 0.0;
        };
    }

    private double freeWaterFlow(V3DryMeshState state) {
        return problem.waterVaporFlowMolPerSecond(1) - waterOverheadSlip(state);
    }

    private static double hydrocarbonVaporTotal(V3DryMeshState state, int node) {
        double total = 0.0;
        for (int component = 0; component < state.componentCount(); component++) total += state.vaporFlow(node, component);
        return total;
    }

    private static double relativeDifference(double left, double right) {
        return Math.abs(left - right) / Math.max(1.0, Math.max(Math.abs(left), Math.abs(right)));
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

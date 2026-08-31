package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.Objects;

/**
 * Constructs a finite dry-MESH seed through a sequential-modular PR material/VLE preconditioner.
 *
 * <p>Like a Wang-Henke bubble-point initializer, it partitions the component material equations from the phase
 * update: given staged temperatures and PR K-values, it solves one tridiagonal material system per active component,
 * updates phase compositions/K-values, and repeats a small bounded number of times. This is only a cold numerical
 * seed; the simultaneous MESH solver and independent audit still exclusively own publication.</p>
 */
final class V3ColumnInitializer {
    private static final double TWO_PHASE_EXTERNAL_LIQUID_FRACTION = 0.20;
    private static final double TWO_PHASE_EXTERNAL_VAPOR_FRACTION = 0.10;
    private static final double VAPOR_ONLY_EXTERNAL_VAPOR_FRACTION = 0.30;
    private static final double ZERO_REFLUX_TRAFFIC_FRACTION = 0.10;
    private static final int COLD_TRAFFIC_GRID_DIVISIONS = 8;
    private static final int MAXIMUM_COLD_TRAFFIC_SWEEPS = 20;
    private static final int MAXIMUM_PARTIAL_CONDENSER_TEAR_SWEEPS = 100;
    private static final double MINIMUM_PHASE_RATIO = 1.0e-12;
    private static final double MAXIMUM_PHASE_RATIO = 1.0e12;
    private static final double COLD_TRAFFIC_DAMPING = 0.5;
    private static final double PHASE_AWARE_VAPOR_DAMPING = 0.25;
    private static final int PHASE_AWARE_SWEEPS = 3;
    private static final double BUBBLE_POINT_DIFFERENCE_KELVIN = 1.0;
    private static final double BUBBLE_POINT_MAXIMUM_TEMPERATURE_CHANGE_KELVIN = 5.0;
    private static final double BUBBLE_POINT_DAMPING = 0.5;
    private static final int MATERIAL_BALANCE_PROJECTION_SWEEPS = 3;
    private static final double MATERIAL_BALANCE_PROJECTION_DAMPING = 0.5;

    private V3ColumnInitializer() {}

    static Seed initialize(V3ColumnProblem problem, V3ThermoModel thermo, V3ThermoWorkspace workspace) {
        return initialize(problem, thermo, workspace, Mode.MATERIAL_CLOSED);
    }

    /** Package-private selector used only by cold-start QA while preconditioners are qualified. */
    static Seed initialize(
            V3ColumnProblem problem, V3ThermoModel thermo, V3ThermoWorkspace workspace, Mode mode) {
        problem = Objects.requireNonNull(problem, "problem");
        thermo = Objects.requireNonNull(thermo, "thermo");
        workspace = Objects.requireNonNull(workspace, "workspace");
        mode = Objects.requireNonNull(mode, "mode");
        if (!problem.input().componentBasis().equals(thermo.componentBasis())) {
            throw new IllegalArgumentException("V3 initializer thermodynamic basis differs from the resolved problem basis");
        }
        int components = problem.activeComponentBasis().componentCount();
        double[] publicFeed = problem.input().feedComponentMolarFlowsMolPerSecond();
        double totalFeed = 0.0;
        for (double flow : publicFeed) totalFeed += flow;
        double[] feedComposition = new double[publicFeed.length];
        for (int component = 0; component < publicFeed.length; component++) feedComposition[component] = publicFeed[component] / totalFeed;
        int feedNode = problem.topology().feedTrayNumber();
        V3FlashResult feedFlash = thermo.flashTP(problem.input().feedTemperatureKelvin(), problem.nodePressurePascal(feedNode),
                feedComposition, workspace);
        double refluxRatio = specification(problem, V3ColumnSpecification.OrganicRefluxRatio.class).ratio();
        V3ColumnTopology topology = problem.topology();
        int nodes = topology.nodeCount();
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        double[] temperatures = temperatures(problem, nodes);

        for (int component = 0; component < components; component++) {
            double feed = problem.activeComponentBasis().feedFlowMolPerSecond(component);
            boolean condenserLiquidComponent = problem.condenserComponentPhases().hasLiquid(topology, 0, component);
            double externalLiquid = condenserLiquidComponent ? TWO_PHASE_EXTERNAL_LIQUID_FRACTION * feed : 0.0;
            double externalVapor = topology.hasVaporPhase(0)
                    ? (topology.hasLiquidPhase(0) ? TWO_PHASE_EXTERNAL_VAPOR_FRACTION
                    : VAPOR_ONLY_EXTERNAL_VAPOR_FRACTION) * feed : 0.0;
            if (condenserLiquidComponent) liquid[0][component] = (1.0 + refluxRatio) * externalLiquid;
            vapor[0][component] = externalVapor;
            vapor[1][component] = vapor[0][component] + liquid[0][component];

            double reflux = condenserLiquidComponent ? refluxRatio * externalLiquid : 0.0;
            double traffic = condenserLiquidComponent && refluxRatio > 0.0
                    ? reflux : ZERO_REFLUX_TRAFFIC_FRACTION * feed;
            double previousLiquid = traffic;
            for (int tray = 1; tray <= topology.trayCount(); tray++) {
                double feedAtTray = tray == topology.feedTrayNumber() ? feed : 0.0;
                liquid[tray][component] = previousLiquid + feedAtTray;
                double liquidIn = tray == 1 ? reflux : previousLiquid;
                vapor[tray + 1][component] = liquid[tray][component] + vapor[tray][component] - liquidIn - feedAtTray;
                if (!Double.isFinite(vapor[tray + 1][component]) || vapor[tray + 1][component] <= 0.0) {
                    throw new IllegalArgumentException("V3 initializer generated a nonpositive stage vapor seed");
                }
                previousLiquid = liquid[tray][component];
            }
            liquid[topology.reboilerNode()][component] = liquid[topology.trayCount()][component]
                    - vapor[topology.reboilerNode()][component];
            if (!Double.isFinite(liquid[topology.reboilerNode()][component])
                    || liquid[topology.reboilerNode()][component] <= 0.0) {
                throw new IllegalArgumentException("V3 initializer generated a nonpositive bottoms seed");
            }
        }
        V3DryMeshState materialClosed = new V3DryMeshState(topology, components, liquid, vapor, temperatures);
        V3DryMeshState state = materialClosed;
        String trafficPolicy = refluxRatio == 0.0
                ? "zero-reflux finite traffic seed" : "organic-reflux material-closed traffic seed";
        try {
            if (mode == Mode.PHASE_AWARE_TRAFFIC) {
                state = phaseAwareTrafficSeed(problem, thermo, workspace, materialClosed);
                trafficPolicy = "PR phase-aware fixed-traffic seed";
            } else if (mode == Mode.SEQUENTIAL_MATERIAL_VLE) {
                state = sequentialModularSeed(problem, thermo, workspace, materialClosed, feedFlash.vaporFraction(),
                        feedFlash.molarEnthalpyJoulesPerMol());
                trafficPolicy = "sequential-modular PR material/VLE seed";
            }
        } catch (V3ThermoException | IllegalArgumentException unavailablePreconditioner) {
            // Retaining the exact material-closed seed is safer than inventing a phase profile when a registered
            // property call cannot provide a physical root. The later rigorous solve returns its normal typed result.
            state = materialClosed;
            trafficPolicy = refluxRatio == 0.0 ? "zero-reflux finite traffic seed" : "organic-reflux material-closed traffic seed";
        }
        return new Seed(state, new Evidence(feedFlash.phase().name(), feedFlash.vaporFraction(),
                trafficPolicy));
    }

    /**
     * Applies a bounded Wang-Henke-style material/VLE projection to an existing cold state.
     *
     * <p>The temperature profile is intentionally held fixed. Each sweep calculates PR phase ratios from the
     * current compositions, solves the component tridiagonal material balances, and reconstructs the corresponding
     * phase flows. This is a solver recovery preconditioner only; a subsequent simultaneous MESH solve and fresh
     * audit remain required before publication.</p>
     */
    static V3DryMeshState projectMaterialBalancesAtFixedTemperature(
            V3ColumnProblem problem, V3ThermoModel thermo, V3ThermoWorkspace workspace, V3DryMeshState state) {
        problem = Objects.requireNonNull(problem, "problem");
        thermo = Objects.requireNonNull(thermo, "thermo");
        workspace = Objects.requireNonNull(workspace, "workspace");
        state = Objects.requireNonNull(state, "state");
        if (state.nodeCount() != problem.topology().nodeCount()
                || state.componentCount() != problem.activeComponentBasis().componentCount()) {
            throw new IllegalArgumentException("V3 material projection state does not match its problem");
        }
        V3ColumnTopology topology = problem.topology();
        double[][] liquid = flows(state, true);
        double[][] vapor = flows(state, false);
        double[] temperatures = temperatures(state);
        double[] fixedTotalVaporToLiquidRatios = totalPhaseFlowRatios(liquid, vapor);
        for (int sweep = 0; sweep < MATERIAL_BALANCE_PROJECTION_SWEEPS; sweep++) {
            double[][] updatedLiquid = copyFlows(liquid);
            double[][] updatedVapor = copyFlows(vapor);
            double[][] phaseFlowRatios = phaseFlowRatios(
                    phaseRatios(problem, thermo, workspace, liquid, vapor, temperatures),
                    fixedTotalVaporToLiquidRatios);
            solveMaterialBalancesWithPhaseFlowRatios(problem, phaseFlowRatios, updatedLiquid, updatedVapor);
            blendFlows(liquid, updatedLiquid, MATERIAL_BALANCE_PROJECTION_DAMPING);
            blendFlows(vapor, updatedVapor, MATERIAL_BALANCE_PROJECTION_DAMPING);
            updateBubblePointTemperatures(problem, thermo, workspace, liquid, vapor, temperatures);
        }
        return new V3DryMeshState(topology, state.componentCount(), liquid, vapor, temperatures);
    }

    private static double[][] copyFlows(double[][] source) {
        double[][] copy = new double[source.length][];
        for (int node = 0; node < source.length; node++) copy[node] = source[node].clone();
        return copy;
    }

    private static void blendFlows(double[][] current, double[][] target, double damping) {
        if (!Double.isFinite(damping) || damping <= 0.0 || damping > 1.0 || current.length != target.length) {
            throw new IllegalArgumentException("V3 material projection flow damping is invalid");
        }
        for (int node = 0; node < current.length; node++) {
            if (current[node].length != target[node].length) {
                throw new IllegalArgumentException("V3 material projection flow rows disagree");
            }
            for (int component = 0; component < current[node].length; component++) {
                double value = current[node][component] + damping * (target[node][component] - current[node][component]);
                if (!Double.isFinite(value) || value < 0.0) {
                    throw new IllegalArgumentException("V3 material projection generated an invalid component flow");
                }
                current[node][component] = value;
            }
        }
    }

    static void solveMaterialBalances(
            V3ColumnProblem problem, double[][] equilibriumConstants, double[][] liquid, double[][] vapor) {
        double[][] phaseFlowRatios = phaseFlowRatios(equilibriumConstants, liquid, vapor);
        solveMaterialBalancesWithPhaseFlowRatios(problem, phaseFlowRatios, liquid, vapor);
    }

    private static void solveMaterialBalancesWithPhaseFlowRatios(
            V3ColumnProblem problem, double[][] phaseFlowRatios, double[][] liquid, double[][] vapor) {
        V3ColumnTopology topology = problem.topology();
        for (int component = 0; component < liquid[0].length; component++) {
            double[] componentLiquid = problem.condenserComponentPhases().isVaporOnlyAtCondenser(component)
                    ? solveVaporOnlyCondenserComponentMaterialBalance(
                            problem, component, phaseRatiosColumn(phaseFlowRatios, component))
                    : solveComponentMaterialBalance(
                            problem, component, phaseRatiosColumn(phaseFlowRatios, component), organicRefluxFraction(problem));
            for (int node = 0; node < topology.nodeCount(); node++) {
                liquid[node][component] = componentLiquid[node];
                vapor[node][component] = node == topology.condenserNode()
                        && problem.condenserComponentPhases().isVaporOnlyAtCondenser(component)
                        ? phaseFlowRatios[1][component] * componentLiquid[1]
                        : phaseFlowRatios[node][component] * componentLiquid[node];
            }
        }
    }

    /**
     * Converts thermodynamic K=y/x values into the component flow ratios V_i/L_i required by the material TDMA.
     *
     * <p>The stage-total V/L factor comes from the prior iterate, exactly as in a sequential Wang-Henke material
     * update. Treating K itself as a component molar-flow ratio is only valid when the two phase totals happen to
     * match, and destabilizes heavy-end recovery at low pressure.</p>
     */
    static double[][] phaseFlowRatios(
            double[][] equilibriumConstants, double[][] liquid, double[][] vapor) {
        if (equilibriumConstants.length != liquid.length || liquid.length != vapor.length) {
            throw new IllegalArgumentException("V3 material phase-ratio grids disagree");
        }
        return phaseFlowRatios(equilibriumConstants, totalPhaseFlowRatios(liquid, vapor));
    }

    private static double[][] phaseFlowRatios(double[][] equilibriumConstants, double[] totalFlowRatios) {
        if (equilibriumConstants.length != totalFlowRatios.length) {
            throw new IllegalArgumentException("V3 material phase-ratio total-flow grid disagrees");
        }
        double[][] ratios = new double[equilibriumConstants.length][];
        for (int node = 0; node < ratios.length; node++) {
            ratios[node] = new double[equilibriumConstants[node].length];
            for (int component = 0; component < ratios[node].length; component++) {
                ratios[node][component] = totalFlowRatios[node] == 0.0 ? 0.0
                        : boundedRatio(totalFlowRatios[node] * equilibriumConstants[node][component]);
            }
        }
        return ratios;
    }

    private static double[] totalPhaseFlowRatios(double[][] liquid, double[][] vapor) {
        if (liquid.length != vapor.length) {
            throw new IllegalArgumentException("V3 material phase-total grids disagree");
        }
        double[] ratios = new double[liquid.length];
        for (int node = 0; node < ratios.length; node++) {
            if (liquid[node].length != vapor[node].length) {
                throw new IllegalArgumentException("V3 material phase-total row dimensions disagree");
            }
            double liquidTotal = sum(liquid[node]);
            double vaporTotal = sum(vapor[node]);
            if (!(liquidTotal > 0.0) || (node == 0 ? vaporTotal < 0.0 : !(vaporTotal > 0.0))
                    || !Double.isFinite(liquidTotal) || !Double.isFinite(vaporTotal)) {
                throw new IllegalArgumentException("V3 material phase-ratio update has no positive phase total");
            }
            ratios[node] = vaporTotal / liquidTotal;
        }
        return ratios;
    }

    private static double[] phaseRatiosColumn(double[][] phaseRatios, int component) {
        double[] result = new double[phaseRatios.length];
        for (int node = 0; node < result.length; node++) result[node] = phaseRatios[node][component];
        return result;
    }

    private static V3DryMeshState phaseAwareTrafficSeed(
            V3ColumnProblem problem, V3ThermoModel thermo, V3ThermoWorkspace workspace, V3DryMeshState materialClosed) {
        double[][] liquid = flows(materialClosed, true);
        double[][] vapor = flows(materialClosed, false);
        for (int sweep = 0; sweep < PHASE_AWARE_SWEEPS; sweep++) {
            double[][] kValues = phaseRatios(problem, thermo, workspace, liquid, vapor, temperatures(materialClosed));
            for (int node = 0; node < vapor.length; node++) {
                if (!problem.topology().hasVaporPhase(node)) continue;
                double vaporTotal = 0.0;
                double liquidTotal = 0.0;
                for (double flow : vapor[node]) vaporTotal += flow;
                for (double flow : liquid[node]) liquidTotal += flow;
                double[] targetVaporFractions = new double[vapor[node].length];
                double targetTotal = 0.0;
                for (int component = 0; component < targetVaporFractions.length; component++) {
                    targetVaporFractions[component] = kValues[node][component] * liquid[node][component] / liquidTotal;
                    targetTotal += targetVaporFractions[component];
                }
                if (!Double.isFinite(targetTotal) || targetTotal <= 0.0) {
                    throw new IllegalArgumentException("V3 phase-aware seed has no positive vapor composition");
                }
                for (int component = 0; component < targetVaporFractions.length; component++) {
                    double currentFraction = vapor[node][component] / vaporTotal;
                    double targetFraction = targetVaporFractions[component] / targetTotal;
                    vapor[node][component] = vaporTotal * ((1.0 - PHASE_AWARE_VAPOR_DAMPING) * currentFraction
                            + PHASE_AWARE_VAPOR_DAMPING * targetFraction);
                }
            }
        }
        return new V3DryMeshState(problem.topology(), materialClosed.componentCount(), liquid, vapor,
                temperatures(materialClosed));
    }

    private static V3DryMeshState sequentialModularSeed(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            V3DryMeshState materialClosed,
            double feedVaporFraction,
            double feedMolarEnthalpyJoulesPerMol) {
        V3ColumnTopology topology = problem.topology();
        if (!topology.hasLiquidPhase(topology.condenserNode())) return materialClosed;
        double feedFlow = problem.activeComponentBasis().totalFeedFlowMolPerSecond();
        double[] temperatures = temperatures(materialClosed);
        double[][] kValues = phaseRatios(problem, thermo, workspace, flows(materialClosed, true), flows(materialClosed, false),
                temperatures);
        ColdTrafficCandidate best = null;
        for (int liquidProductStep = 1; liquidProductStep < COLD_TRAFFIC_GRID_DIVISIONS; liquidProductStep++) {
            for (int vaporProductStep = topology.hasVaporPhase(0) ? 1 : 0;
                 liquidProductStep + vaporProductStep < COLD_TRAFFIC_GRID_DIVISIONS
                         && (topology.hasVaporPhase(0) || vaporProductStep == 0);
                 vaporProductStep++) {
                ColdTrafficCandidate candidate = coldTrafficCandidate(
                        problem, kValues, feedFlow, feedVaporFraction,
                        liquidProductStep / (double) COLD_TRAFFIC_GRID_DIVISIONS,
                        vaporProductStep / (double) COLD_TRAFFIC_GRID_DIVISIONS);
                if (candidate != null && (best == null || candidate.sumRatesMismatch() < best.sumRatesMismatch())) {
                    best = candidate;
                }
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("V3 sequential modular initializer found no positive cold traffic candidate");
        }
        ColdTrafficCandidate refreshed = refinePartialCondenserTraffic(
                problem, thermo, workspace, kValues, feedFlow, feedVaporFraction, feedMolarEnthalpyJoulesPerMol,
                best, temperatures);
        return new V3DryMeshState(topology, materialClosed.componentCount(), refreshed.liquidFlows(), refreshed.vaporFlows(),
                temperatures);
    }

    /** Applies a bounded bubble-point Newton correction on every non-condenser equilibrium node. */
    private static double updateBubblePointTemperatures(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            ColdTrafficCandidate candidate,
            double[] temperatures) {
        return updateBubblePointTemperatures(
                problem, thermo, workspace, candidate.liquidFlows(), candidate.vaporFlows(), temperatures);
    }

    private static double updateBubblePointTemperatures(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            double[][] liquidFlows,
            double[][] vaporFlows,
            double[] temperatures) {
        V3ColumnTopology topology = problem.topology();
        V3ActiveComponentBasis active = problem.activeComponentBasis();
        double maximumChange = 0.0;
        for (int node = 1; node <= topology.reboilerNode(); node++) {
            double temperature = temperatures[node];
            double residual = bubblePointResidual(problem, thermo, workspace, active,
                    liquidFlows[node], vaporFlows[node], node, temperature);
            double upperResidual = bubblePointResidual(problem, thermo, workspace, active,
                    liquidFlows[node], vaporFlows[node], node,
                    temperature + BUBBLE_POINT_DIFFERENCE_KELVIN);
            double lowerResidual = bubblePointResidual(problem, thermo, workspace, active,
                    liquidFlows[node], vaporFlows[node], node,
                    temperature - BUBBLE_POINT_DIFFERENCE_KELVIN);
            double derivative = (upperResidual - lowerResidual) / (2.0 * BUBBLE_POINT_DIFFERENCE_KELVIN);
            if (!Double.isFinite(residual) || !Double.isFinite(derivative) || Math.abs(derivative) <= 1.0e-12) continue;
            double correction = Math.clamp(-residual / derivative,
                    -BUBBLE_POINT_MAXIMUM_TEMPERATURE_CHANGE_KELVIN,
                    BUBBLE_POINT_MAXIMUM_TEMPERATURE_CHANGE_KELVIN);
            double updated = temperature + BUBBLE_POINT_DAMPING * correction;
            if (Double.isFinite(updated) && updated > 0.0) {
                temperatures[node] = updated;
                maximumChange = Math.max(maximumChange, Math.abs(updated - temperature));
            }
        }
        return maximumChange;
    }

    private static double bubblePointResidual(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            V3ActiveComponentBasis active,
            double[] liquidFlows,
            double[] vaporFlows,
            int node,
            double temperatureKelvin) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin <= 0.0) return Double.NaN;
        double[] liquidComposition = publicComposition(active, liquidFlows);
        double[] vaporComposition = publicComposition(active, vaporFlows);
        double pressure = problem.nodePressurePascal(node);
        V3FugacityResult liquid = thermo.fugacity(
                temperatureKelvin, pressure, liquidComposition, V3Phase.LIQUID, workspace);
        V3FugacityResult vapor = thermo.fugacity(
                temperatureKelvin, pressure, vaporComposition, V3Phase.VAPOR, workspace);
        double sum = 0.0;
        for (int component = 0; component < active.componentCount(); component++) {
            int publicComponent = active.publicIndex(component);
            sum += liquidComposition[publicComponent] * Math.exp(
                    liquid.logFugacityCoefficient(publicComponent) - vapor.logFugacityCoefficient(publicComponent));
        }
        return sum - 1.0;
    }

    /**
     * DWSIM-style condenser traffic tear. The liquid node is split into reflux and liquid distillate;
     * a partial condenser also has an independently solved vapor product.
     */
    private static ColdTrafficCandidate refinePartialCondenserTraffic(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            double[][] kValues,
            double feedFlow,
            double feedVaporFraction,
            double feedMolarEnthalpyJoulesPerMol,
            ColdTrafficCandidate initial,
            double[] temperatures) {
        ColdTrafficCandidate current = initial;
        double liquidProductFraction = initial.liquidProductFraction();
        for (int sweep = 0; sweep < MAXIMUM_PARTIAL_CONDENSER_TEAR_SWEEPS; sweep++) {
            double maximumTemperatureChange = updateBubblePointTemperatures(
                    problem, thermo, workspace, current, temperatures);
            double[][] currentK = phaseRatios(
                    problem, thermo, workspace, current.liquidFlows(), current.vaporFlows(), temperatures);
            double currentKChange = blendLogPhaseRatios(kValues, currentK, COLD_TRAFFIC_DAMPING);
            EnergyProperties enthalpies = energyProperties(problem, thermo, workspace, current, temperatures);
            if (enthalpies == null) break;
            ColdTrafficCandidate energyRefreshed = selectPartialCondenserEnergyCandidate(
                    problem, kValues, feedFlow, feedVaporFraction, liquidProductFraction, enthalpies,
                    feedMolarEnthalpyJoulesPerMol);
            if (energyRefreshed == null) break;
            double actualLiquidProductFraction = (1.0 - organicRefluxFraction(problem))
                    * sum(energyRefreshed.liquidFlows()[problem.topology().condenserNode()]) / feedFlow;
            if (!(actualLiquidProductFraction > 0.0) || actualLiquidProductFraction >= 1.0) break;
            double trafficChange = Math.abs(actualLiquidProductFraction - liquidProductFraction);
            liquidProductFraction += COLD_TRAFFIC_DAMPING
                    * (actualLiquidProductFraction - liquidProductFraction);
            double[][] refreshedK = phaseRatios(problem, thermo, workspace,
                    energyRefreshed.liquidFlows(), energyRefreshed.vaporFlows(), temperatures);
            double refreshedKChange = blendLogPhaseRatios(kValues, refreshedK, COLD_TRAFFIC_DAMPING);
            current = energyRefreshed;
            if (trafficChange <= 1.0e-6 && Math.max(currentKChange, refreshedKChange) <= 1.0e-5
                    && maximumTemperatureChange <= 1.0e-5) break;
        }
        return current;
    }

    private static double blendLogPhaseRatios(double[][] current, double[][] target, double damping) {
        double maximumLogChange = 0.0;
        for (int node = 0; node < current.length; node++) {
            for (int component = 0; component < current[node].length; component++) {
                maximumLogChange = Math.max(maximumLogChange,
                        Math.abs(Math.log(target[node][component]) - Math.log(current[node][component])));
                current[node][component] = Math.exp((1.0 - damping) * Math.log(current[node][component])
                        + damping * Math.log(target[node][component]));
            }
        }
        return maximumLogChange;
    }

    private static ColdTrafficCandidate partialCondenserEnergyCandidate(
            V3ColumnProblem problem,
            double[][] kValues,
            double feedFlow,
            double feedVaporFraction,
            double liquidProductFraction,
            EnergyProperties enthalpies,
            double feedMolarEnthalpyJoulesPerMol) {
        double liquidProduct = liquidProductFraction * feedFlow;
        if (!(liquidProduct > 0.0) || liquidProduct >= feedFlow) return null;
        double vaporProbe = Math.max(feedFlow * 0.1, 1.0e-6);
        double zeroMismatch = reboilerTrafficMismatch(
                problem, enthalpies, feedFlow, liquidProduct, 0.0, feedMolarEnthalpyJoulesPerMol);
        double probeMismatch = reboilerTrafficMismatch(
                problem, enthalpies, feedFlow, liquidProduct, vaporProbe, feedMolarEnthalpyJoulesPerMol);
        double slope = (probeMismatch - zeroMismatch) / vaporProbe;
        if (!Double.isFinite(zeroMismatch) || !Double.isFinite(slope) || Math.abs(slope) <= 1.0e-12) return null;
        double vaporProduct = -zeroMismatch / slope;
        if (!(vaporProduct > 0.0) || liquidProduct + vaporProduct >= feedFlow) return null;
        TrafficTotals traffic = partialCondenserEnergyTrafficTotals(
                problem, enthalpies, feedFlow, liquidProduct, vaporProduct, feedMolarEnthalpyJoulesPerMol);
        if (traffic == null || !positiveFiniteTraffic(traffic)) return null;
        return coldTrafficCandidate(problem, kValues, feedFlow, feedVaporFraction,
                traffic.liquidTotals(), traffic.vaporTotals(), liquidProductFraction, vaporProduct / feedFlow);
    }

    private static ColdTrafficCandidate selectPartialCondenserEnergyCandidate(
            V3ColumnProblem problem,
            double[][] kValues,
            double feedFlow,
            double feedVaporFraction,
            double preferredLiquidProductFraction,
            EnergyProperties enthalpies,
            double feedMolarEnthalpyJoulesPerMol) {
        if (!problem.topology().hasVaporPhase(problem.topology().condenserNode())) {
            return totalCondenserEnergyCandidate(problem, kValues, feedFlow, feedVaporFraction,
                    enthalpies, feedMolarEnthalpyJoulesPerMol);
        }
        ColdTrafficCandidate best = partialCondenserEnergyCandidate(
                problem, kValues, feedFlow, feedVaporFraction, preferredLiquidProductFraction, enthalpies,
                feedMolarEnthalpyJoulesPerMol);
        for (int step = 1; step < 32; step++) {
            ColdTrafficCandidate candidate = partialCondenserEnergyCandidate(
                    problem, kValues, feedFlow, feedVaporFraction, step / 32.0, enthalpies,
                    feedMolarEnthalpyJoulesPerMol);
            if (candidate != null && (best == null || candidate.sumRatesMismatch() < best.sumRatesMismatch())) {
                best = candidate;
            }
        }
        return best;
    }

    /** With no vapor product, the energy recurrence determines liquid distillate flow directly. */
    private static ColdTrafficCandidate totalCondenserEnergyCandidate(
            V3ColumnProblem problem, double[][] kValues, double feedFlow, double feedVaporFraction,
            EnergyProperties enthalpies, double feedMolarEnthalpyJoulesPerMol) {
        double probe = feedFlow * 0.1;
        double zeroMismatch = reboilerTrafficMismatch(
                problem, enthalpies, feedFlow, 0.0, 0.0, feedMolarEnthalpyJoulesPerMol);
        double probeMismatch = reboilerTrafficMismatch(
                problem, enthalpies, feedFlow, probe, 0.0, feedMolarEnthalpyJoulesPerMol);
        double slope = (probeMismatch - zeroMismatch) / probe;
        if (!Double.isFinite(zeroMismatch) || !Double.isFinite(slope) || Math.abs(slope) <= 1.0e-12) return null;
        double liquidProduct = -zeroMismatch / slope;
        if (!(liquidProduct > 0.0) || liquidProduct >= feedFlow) return null;
        TrafficTotals traffic = partialCondenserEnergyTrafficTotals(
                problem, enthalpies, feedFlow, liquidProduct, 0.0, feedMolarEnthalpyJoulesPerMol);
        if (traffic == null || !positiveFiniteTraffic(traffic)) return null;
        return coldTrafficCandidate(problem, kValues, feedFlow, feedVaporFraction,
                traffic.liquidTotals(), traffic.vaporTotals(), liquidProduct / feedFlow, 0.0);
    }

    private static EnergyProperties energyProperties(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            ColdTrafficCandidate reference,
            double[] temperatures) {
        int nodes = problem.topology().nodeCount();
        double[] liquidEnthalpy = new double[nodes];
        double[] vaporEnthalpy = new double[nodes];
        V3ActiveComponentBasis active = problem.activeComponentBasis();
        for (int node = 0; node < nodes; node++) {
            try {
                double pressure = problem.nodePressurePascal(node);
                liquidEnthalpy[node] = thermo.molarEnthalpy(temperatures[node], pressure,
                        publicComposition(active, reference.liquidFlows()[node]), V3Phase.LIQUID, workspace);
                if (problem.topology().hasVaporPhase(node)) {
                    vaporEnthalpy[node] = thermo.molarEnthalpy(temperatures[node], pressure,
                            publicComposition(active, reference.vaporFlows()[node]), V3Phase.VAPOR, workspace);
                }
            } catch (V3ThermoException | IllegalArgumentException unavailable) {
                return null;
            }
            if (!Double.isFinite(liquidEnthalpy[node]) || !Double.isFinite(vaporEnthalpy[node])) return null;
        }
        return new EnergyProperties(liquidEnthalpy, vaporEnthalpy);
    }

    private static double reboilerTrafficMismatch(
            V3ColumnProblem problem,
            EnergyProperties enthalpies,
            double feedFlow,
            double liquidProduct,
            double vaporProduct,
            double feedMolarEnthalpyJoulesPerMol) {
        TrafficTotals traffic = partialCondenserEnergyTrafficTotals(
                problem, enthalpies, feedFlow, liquidProduct, vaporProduct, feedMolarEnthalpyJoulesPerMol);
        if (traffic == null) return Double.NaN;
        return traffic.trayPredictedReboilerVapor() - traffic.vaporTotals()[problem.topology().reboilerNode()];
    }

    private static TrafficTotals partialCondenserEnergyTrafficTotals(
            V3ColumnProblem problem,
            EnergyProperties enthalpies,
            double feedFlow,
            double liquidProduct,
            double vaporProduct,
            double feedMolarEnthalpyJoulesPerMol) {
        V3ColumnTopology topology = problem.topology();
        int reboiler = topology.reboilerNode();
        double bottoms = feedFlow - liquidProduct - vaporProduct;
        if (!Double.isFinite(bottoms)) return null;
        double[] liquid = new double[reboiler + 1];
        double[] vapor = new double[reboiler + 1];
        double refluxRatio = specification(problem, V3ColumnSpecification.OrganicRefluxRatio.class).ratio();
        liquid[0] = (1.0 + refluxRatio) * liquidProduct;
        vapor[0] = vaporProduct;
        vapor[1] = liquid[0] + vapor[0];
        double feedBefore = 0.0;
        for (int tray = 1; tray <= topology.trayCount(); tray++) {
            double feedAtTray = tray == topology.feedTrayNumber() ? feedFlow : 0.0;
            double alpha = enthalpies.liquid()[tray - 1] - enthalpies.vapor()[tray];
            double beta = enthalpies.vapor()[tray + 1] - enthalpies.liquid()[tray];
            double gamma = (feedBefore - liquidProduct - vaporProduct)
                    * (enthalpies.liquid()[tray] - enthalpies.liquid()[tray - 1])
                    + feedAtTray * (enthalpies.liquid()[tray] - feedMolarEnthalpyJoulesPerMol);
            if (!Double.isFinite(alpha) || !Double.isFinite(beta) || !Double.isFinite(gamma)
                    || Math.abs(beta) <= 1.0e-12) return null;
            double nextVapor = (gamma - alpha * vapor[tray]) / beta;
            if (tray < topology.trayCount()) {
                vapor[tray + 1] = nextVapor;
                feedBefore += feedAtTray;
                continue;
            }
            double reboilerDuty = specification(problem, V3ColumnSpecification.ReboilerDuty.class).watts();
            double reboilerVapor = (bottoms * (enthalpies.liquid()[reboiler]
                    - enthalpies.liquid()[topology.trayCount()]) - reboilerDuty)
                    / (enthalpies.liquid()[topology.trayCount()] - enthalpies.vapor()[reboiler]);
            if (!Double.isFinite(nextVapor) || !Double.isFinite(reboilerVapor)) return null;
            vapor[reboiler] = reboilerVapor;
            double cumulativeFeed = 0.0;
            for (int liquidTray = 1; liquidTray <= topology.trayCount(); liquidTray++) {
                if (liquidTray == topology.feedTrayNumber()) cumulativeFeed += feedFlow;
                liquid[liquidTray] = vapor[liquidTray + 1] + cumulativeFeed - liquidProduct - vaporProduct;
                if (!Double.isFinite(liquid[liquidTray])) return null;
            }
            liquid[reboiler] = bottoms;
            if (!Double.isFinite(liquid[reboiler])) return null;
            return new TrafficTotals(liquid, vapor, nextVapor);
        }
        throw new IllegalStateException("V3 partial-condenser energy recurrence did not reach the reboiler");
    }

    private static ColdTrafficCandidate coldTrafficCandidate(
            V3ColumnProblem problem,
            double[][] kValues,
            double feedFlow,
            double feedVaporFraction,
            double liquidProductFraction,
            double vaporProductFraction) {
        V3ColumnTopology topology = problem.topology();
        int nodes = topology.nodeCount();
        int components = problem.activeComponentBasis().componentCount();
        double[] liquidTotals = new double[nodes];
        double[] vaporTotals = new double[nodes];
        if (!buildColdTrafficTotals(problem, feedFlow, feedVaporFraction, liquidProductFraction, vaporProductFraction,
                liquidTotals, vaporTotals)) {
            return null;
        }
        return coldTrafficCandidate(problem, kValues, feedFlow, feedVaporFraction,
                liquidTotals, vaporTotals, liquidProductFraction, vaporProductFraction);
    }

    private static ColdTrafficCandidate coldTrafficCandidate(
            V3ColumnProblem problem,
            double[][] kValues,
            double feedFlow,
            double feedVaporFraction,
            double[] liquidTotals,
            double[] vaporTotals,
            double liquidProductFraction,
            double vaporProductFraction) {
        int nodes = problem.topology().nodeCount();
        int components = problem.activeComponentBasis().componentCount();
        if (liquidTotals.length != nodes || vaporTotals.length != nodes) {
            throw new IllegalArgumentException("V3 partial-condenser traffic dimensions disagree");
        }
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        for (int component = 0; component < components; component++) {
            double[] componentPhaseRatios = new double[nodes];
            for (int node = 0; node < nodes; node++) {
                componentPhaseRatios[node] = vaporTotals[node] == 0.0 ? 0.0 : boundedRatio(
                        kValues[node][component] * vaporTotals[node] / liquidTotals[node]);
            }
            double[] componentLiquid;
            try {
                componentLiquid = problem.condenserComponentPhases().isVaporOnlyAtCondenser(component)
                        ? solveVaporOnlyCondenserComponentMaterialBalance(problem, component, componentPhaseRatios)
                        : solveComponentMaterialBalance(
                                problem, component, componentPhaseRatios, organicRefluxFraction(problem));
            } catch (IllegalArgumentException singularOrNegative) {
                return null;
            }
            for (int node = 0; node < nodes; node++) {
                liquid[node][component] = componentLiquid[node];
                vapor[node][component] = node == 0 && problem.condenserComponentPhases().isVaporOnlyAtCondenser(component)
                        ? componentPhaseRatios[1] * componentLiquid[1]
                        : componentPhaseRatios[node] * componentLiquid[node];
            }
        }
        double mismatch = sumRatesMismatch(liquid, vapor, liquidTotals, vaporTotals);
        return Double.isFinite(mismatch)
                ? new ColdTrafficCandidate(liquid, vapor, mismatch, liquidProductFraction, vaporProductFraction) : null;
    }

    private static boolean positiveFiniteTraffic(TrafficTotals traffic) {
        double[] liquid = traffic.liquidTotals();
        double[] vapor = traffic.vaporTotals();
        if (liquid.length != vapor.length) return false;
        for (int node = 0; node < liquid.length; node++) {
            if (!(liquid[node] > 0.0) || (node == 0 ? vapor[node] < 0.0 : !(vapor[node] > 0.0))
                    || !Double.isFinite(liquid[node]) || !Double.isFinite(vapor[node])) return false;
        }
        return true;
    }

    private static boolean buildColdTrafficTotals(
            V3ColumnProblem problem,
            double feedFlow,
            double feedVaporFraction,
            double liquidProductFraction,
            double vaporProductFraction,
            double[] liquidTotals,
            double[] vaporTotals) {
        double liquidProduct = liquidProductFraction * feedFlow;
        double overheadVapor = vaporProductFraction * feedFlow;
        double bottoms = feedFlow - liquidProduct - overheadVapor;
        double refluxFraction = organicRefluxFraction(problem);
        boolean hasVaporProduct = problem.topology().hasVaporPhase(problem.topology().condenserNode());
        if (!(liquidProduct > 0.0) || (hasVaporProduct ? !(overheadVapor > 0.0) : overheadVapor != 0.0)
                || !(bottoms > 0.0) || refluxFraction >= 1.0) return false;
        double condensate = liquidProduct / (1.0 - refluxFraction);
        if (!Double.isFinite(condensate) || condensate <= 0.0) return false;
        liquidTotals[0] = condensate;
        vaporTotals[0] = overheadVapor;
        double incomingLiquid = refluxFraction * condensate;
        double upwardVapor = condensate + overheadVapor;
        V3ColumnTopology topology = problem.topology();
        for (int tray = 1; tray <= topology.trayCount(); tray++) {
            double liquidFeed = tray == topology.feedTrayNumber() ? (1.0 - feedVaporFraction) * feedFlow : 0.0;
            double vaporFeed = tray == topology.feedTrayNumber() ? feedVaporFraction * feedFlow : 0.0;
            double liquidOut = incomingLiquid + liquidFeed;
            if (!(liquidOut > 0.0) || !(upwardVapor > 0.0)) return false;
            liquidTotals[tray] = liquidOut;
            vaporTotals[tray] = upwardVapor;
            incomingLiquid = liquidOut;
            upwardVapor -= vaporFeed;
        }
        double balanceClosedBottoms = incomingLiquid - upwardVapor;
        if (!(balanceClosedBottoms > 0.0) || !(upwardVapor > 0.0)
                || Math.abs(balanceClosedBottoms - bottoms) > 1.0e-10 * Math.max(1.0, feedFlow)) return false;
        liquidTotals[topology.reboilerNode()] = balanceClosedBottoms;
        vaporTotals[topology.reboilerNode()] = upwardVapor;
        return true;
    }

    private static double sumRatesMismatch(
            double[][] liquid, double[][] vapor, double[] liquidTotals, double[] vaporTotals) {
        double mismatch = 0.0;
        for (int node = 0; node < liquid.length; node++) {
            double liquidSum = 0.0;
            double vaporSum = 0.0;
            for (int component = 0; component < liquid[node].length; component++) {
                liquidSum += liquid[node][component];
                vaporSum += vapor[node][component];
            }
            if (!(liquidSum > 0.0)) return Double.POSITIVE_INFINITY;
            mismatch = Math.max(mismatch, Math.abs(Math.log(liquidSum / liquidTotals[node])));
            if (node == 0 && vaporSum == 0.0 && vaporTotals[node] == 0.0) continue;
            if (!(vaporSum > 0.0)) return Double.POSITIVE_INFINITY;
            mismatch = Math.max(mismatch, Math.abs(Math.log(vaporSum / vaporTotals[node])));
        }
        return mismatch;
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    static double[][] phaseRatios(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            double[][] liquid,
            double[][] vapor,
            double[] temperatures) {
        V3ActiveComponentBasis active = problem.activeComponentBasis();
        double[][] ratios = new double[liquid.length][active.componentCount()];
        for (int node = 0; node < liquid.length; node++) {
            if (!problem.topology().hasVaporPhase(node)) {
                java.util.Arrays.fill(ratios[node], 1.0);
                continue;
            }
            double[] liquidComposition = publicComposition(active, liquid[node]);
            double[] vaporComposition = publicComposition(active, vapor[node]);
            V3FugacityResult liquidResult = thermo.fugacity(
                    temperatures[node], problem.nodePressurePascal(node), liquidComposition,
                    V3Phase.LIQUID, workspace);
            V3FugacityResult vaporResult = thermo.fugacity(
                    temperatures[node], problem.nodePressurePascal(node), vaporComposition,
                    V3Phase.VAPOR, workspace);
            for (int component = 0; component < active.componentCount(); component++) {
                int publicComponent = active.publicIndex(component);
                ratios[node][component] = boundedRatio(Math.exp(
                        liquidResult.logFugacityCoefficient(publicComponent)
                                - vaporResult.logFugacityCoefficient(publicComponent)));
            }
        }
        return ratios;
    }

    private static double[] solveComponentMaterialBalance(
            V3ColumnProblem problem, int component, double[] phaseRatios, double refluxFraction) {
        V3ColumnTopology topology = problem.topology();
        int reboiler = topology.reboilerNode();
        double[] lower = new double[reboiler + 1];
        double[] diagonal = new double[reboiler + 1];
        double[] upper = new double[reboiler + 1];
        double[] rightHandSide = new double[reboiler + 1];

        diagonal[0] = -(1.0 + phaseRatios[0]);
        upper[0] = phaseRatios[1];
        for (int tray = 1; tray <= topology.trayCount(); tray++) {
            lower[tray] = tray == 1 ? refluxFraction : 1.0;
            diagonal[tray] = -(1.0 + phaseRatios[tray]);
            upper[tray] = phaseRatios[tray + 1];
            rightHandSide[tray] = tray == topology.feedTrayNumber()
                    ? -problem.activeComponentBasis().feedFlowMolPerSecond(component) : 0.0;
        }
        lower[reboiler] = 1.0;
        diagonal[reboiler] = -(1.0 + phaseRatios[reboiler]);
        return solveTridiagonal(lower, diagonal, upper, rightHandSide);
    }

    /** Solves a vapor-only condenser component with V1=V0 and no liquid reflux or condenser VLE row. */
    private static double[] solveVaporOnlyCondenserComponentMaterialBalance(
            V3ColumnProblem problem, int component, double[] phaseRatios) {
        V3ColumnTopology topology = problem.topology();
        int reboiler = topology.reboilerNode();
        double[] lower = new double[reboiler];
        double[] diagonal = new double[reboiler];
        double[] upper = new double[reboiler];
        double[] rightHandSide = new double[reboiler];
        for (int tray = 1; tray <= topology.trayCount(); tray++) {
            int row = tray - 1;
            lower[row] = tray == 1 ? 0.0 : 1.0;
            diagonal[row] = -(1.0 + phaseRatios[tray]);
            upper[row] = phaseRatios[tray + 1];
            rightHandSide[row] = tray == topology.feedTrayNumber()
                    ? -problem.activeComponentBasis().feedFlowMolPerSecond(component) : 0.0;
        }
        int reboilerRow = reboiler - 1;
        lower[reboilerRow] = 1.0;
        diagonal[reboilerRow] = -(1.0 + phaseRatios[reboiler]);
        double[] reducedSolution = solveTridiagonal(lower, diagonal, upper, rightHandSide);
        double[] liquid = new double[reboiler + 1];
        System.arraycopy(reducedSolution, 0, liquid, 1, reducedSolution.length);
        return liquid;
    }

    private static double[] solveTridiagonal(
            double[] lower, double[] diagonal, double[] upper, double[] rightHandSide) {
        int size = diagonal.length;
        if (lower.length != size || upper.length != size || rightHandSide.length != size) {
            throw new IllegalArgumentException("V3 sequential material system dimensions disagree");
        }
        double[] transformedUpper = new double[size];
        double[] transformedRightHandSide = new double[size];
        double pivot = diagonal[0];
        requirePivot(pivot);
        transformedUpper[0] = upper[0] / pivot;
        transformedRightHandSide[0] = rightHandSide[0] / pivot;
        for (int row = 1; row < size; row++) {
            pivot = diagonal[row] - lower[row] * transformedUpper[row - 1];
            requirePivot(pivot);
            transformedUpper[row] = row == size - 1 ? 0.0 : upper[row] / pivot;
            transformedRightHandSide[row] = (rightHandSide[row]
                    - lower[row] * transformedRightHandSide[row - 1]) / pivot;
        }
        double[] solution = new double[size];
        solution[size - 1] = transformedRightHandSide[size - 1];
        for (int row = size - 2; row >= 0; row--) {
            solution[row] = transformedRightHandSide[row] - transformedUpper[row] * solution[row + 1];
        }
        for (double value : solution) {
            if (!Double.isFinite(value) || value <= 0.0) {
                throw new IllegalArgumentException("V3 sequential material preconditioner generated a nonpositive flow");
            }
        }
        return solution;
    }

    static double[][] flows(V3DryMeshState state, boolean liquid) {
        double[][] result = new double[state.nodeCount()][state.componentCount()];
        for (int node = 0; node < result.length; node++) {
            for (int component = 0; component < result[node].length; component++) {
                result[node][component] = liquid ? state.liquidFlow(node, component) : state.vaporFlow(node, component);
            }
        }
        return result;
    }

    private static double[] publicComposition(V3ActiveComponentBasis active, double[] componentFlows) {
        double total = 0.0;
        for (double flow : componentFlows) total += flow;
        if (!Double.isFinite(total) || total <= 0.0) {
            throw new IllegalArgumentException("V3 sequential material preconditioner has no positive phase composition");
        }
        double[] composition = new double[active.publicBasis().componentCount()];
        for (int component = 0; component < componentFlows.length; component++) {
            double flow = componentFlows[component];
            if (!Double.isFinite(flow) || flow < 0.0) {
                throw new IllegalArgumentException("V3 sequential material preconditioner generated an invalid component flow");
            }
            composition[active.publicIndex(component)] = flow == 0.0 ? 0.0 : flow / total;
        }
        return composition;
    }

    static double[] temperatures(V3DryMeshState state) {
        double[] values = new double[state.nodeCount()];
        for (int node = 0; node < values.length; node++) values[node] = state.temperatureKelvin(node);
        return values;
    }

    private static double[] temperatures(V3ColumnProblem problem, int nodes) {
        double condenserTemperature = specification(problem, V3ColumnSpecification.CondenserOutletTemperature.class).kelvin();
        double[] temperatures = new double[nodes];
        for (int node = 0; node < nodes; node++) {
            temperatures[node] = condenserTemperature + (problem.input().feedTemperatureKelvin() - condenserTemperature)
                    * node / (nodes - 1.0);
        }
        return temperatures;
    }

    private static double organicRefluxFraction(V3ColumnProblem problem) {
        double refluxRatio = specification(problem, V3ColumnSpecification.OrganicRefluxRatio.class).ratio();
        return refluxRatio / (1.0 + refluxRatio);
    }

    private static double boundedRatio(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException("V3 sequential material preconditioner generated an invalid phase ratio");
        }
        return Math.clamp(value, MINIMUM_PHASE_RATIO, MAXIMUM_PHASE_RATIO);
    }

    private static void requirePivot(double value) {
        if (!Double.isFinite(value) || Math.abs(value) <= 1.0e-14) {
            throw new IllegalArgumentException("V3 sequential material preconditioner found a singular material system");
        }
    }

    private static <T extends V3ColumnSpecification> T specification(V3ColumnProblem problem, Class<T> type) {
        return problem.input().specifications().stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V3 initializer problem is missing " + type.getSimpleName()));
    }

    record Seed(V3DryMeshState state, Evidence evidence) {
        Seed {
            state = Objects.requireNonNull(state, "state");
            evidence = Objects.requireNonNull(evidence, "evidence");
        }
    }

    private record EnergyProperties(double[] liquid, double[] vapor) {
        private EnergyProperties {
            liquid = liquid.clone();
            vapor = vapor.clone();
        }

        @Override public double[] liquid() { return liquid.clone(); }
        @Override public double[] vapor() { return vapor.clone(); }
    }

    private record TrafficTotals(double[] liquidTotals, double[] vaporTotals, double trayPredictedReboilerVapor) {
        private TrafficTotals {
            liquidTotals = liquidTotals.clone();
            vaporTotals = vaporTotals.clone();
        }

        @Override public double[] liquidTotals() { return liquidTotals.clone(); }
        @Override public double[] vaporTotals() { return vaporTotals.clone(); }
    }

    private record ColdTrafficCandidate(
            double[][] liquidFlows,
            double[][] vaporFlows,
            double sumRatesMismatch,
            double liquidProductFraction,
            double vaporProductFraction) {
        private ColdTrafficCandidate {
            liquidFlows = copy(liquidFlows);
            vaporFlows = copy(vaporFlows);
            if (!Double.isFinite(sumRatesMismatch) || sumRatesMismatch < 0.0) {
                throw new IllegalArgumentException("V3 cold traffic mismatch is invalid");
            }
            if (!Double.isFinite(liquidProductFraction) || !Double.isFinite(vaporProductFraction)
                    || liquidProductFraction <= 0.0 || vaporProductFraction < 0.0
                    || liquidProductFraction + vaporProductFraction >= 1.0) {
                throw new IllegalArgumentException("V3 cold traffic product fractions are invalid");
            }
        }

        @Override public double[][] liquidFlows() { return copy(liquidFlows); }
        @Override public double[][] vaporFlows() { return copy(vaporFlows); }

        private static double[][] copy(double[][] values) {
            double[][] copy = new double[values.length][];
            for (int row = 0; row < values.length; row++) copy[row] = values[row].clone();
            return copy;
        }
    }

    enum Mode {
        MATERIAL_CLOSED,
        PHASE_AWARE_TRAFFIC,
        SEQUENTIAL_MATERIAL_VLE
    }

    record Evidence(String feedPhase, double feedVaporFraction, String trafficPolicy) {
        Evidence {
            if (feedPhase == null || feedPhase.isBlank() || !Double.isFinite(feedVaporFraction)
                    || feedVaporFraction < 0.0 || feedVaporFraction > 1.0 || trafficPolicy == null || trafficPolicy.isBlank()) {
                throw new IllegalArgumentException("V3 initializer evidence is invalid");
            }
        }
    }
}

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
    private static final double MINIMUM_PHASE_RATIO = 1.0e-12;
    private static final double MAXIMUM_PHASE_RATIO = 1.0e12;
    private static final double COLD_TRAFFIC_DAMPING = 0.5;
    private static final double PHASE_AWARE_VAPOR_DAMPING = 0.25;
    private static final int PHASE_AWARE_SWEEPS = 3;
    private static final int MAXIMUM_CONDENSER_TEAR_SWEEPS = 6;
    private static final double CONDENSER_TEAR_DAMPING = 0.35;
    private static final double BUBBLE_POINT_DIFFERENCE_KELVIN = 1.0;
    private static final double BUBBLE_POINT_MAXIMUM_TEMPERATURE_CHANGE_KELVIN = 5.0;
    private static final double BUBBLE_POINT_DAMPING = 0.5;

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
            double externalLiquid = topology.hasLiquidPhase(0) ? TWO_PHASE_EXTERNAL_LIQUID_FRACTION * feed : 0.0;
            double externalVapor = topology.hasVaporPhase(0)
                    ? (topology.hasLiquidPhase(0) ? TWO_PHASE_EXTERNAL_VAPOR_FRACTION
                    : VAPOR_ONLY_EXTERNAL_VAPOR_FRACTION) * feed
                    : 0.0;
            if (topology.hasLiquidPhase(0)) liquid[0][component] = (1.0 + refluxRatio) * externalLiquid;
            vapor[0][component] = externalVapor;
            vapor[1][component] = vapor[0][component] + liquid[0][component];

            double reflux = refluxRatio * externalLiquid;
            double traffic = refluxRatio > 0.0 ? reflux : ZERO_REFLUX_TRAFFIC_FRACTION * feed;
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
            if (mode == Mode.PHASE_AWARE_TRAFFIC && topology.hasVaporLiquidEquilibriumEquation(0)) {
                state = phaseAwareTrafficSeed(problem, thermo, workspace, materialClosed);
                trafficPolicy = "PR phase-aware fixed-traffic seed";
            } else if (mode == Mode.SEQUENTIAL_MATERIAL_VLE && topology.hasVaporLiquidEquilibriumEquation(0)) {
                state = sequentialModularSeed(problem, thermo, workspace, materialClosed, feedFlash.vaporFraction());
                trafficPolicy = "sequential-modular PR material/VLE seed";
            } else if (mode == Mode.SEQUENTIAL_MATERIAL_VLE && topology.hasLiquidPhase(0)) {
                state = totalCondenserSequentialSeed(problem, thermo, workspace, materialClosed, feedFlash.vaporFraction());
                trafficPolicy = "total-condenser sequential PR material/VLE seed";
            } else if (mode != Mode.MATERIAL_CLOSED) {
                trafficPolicy = "single-phase-condenser material-closed seed";
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
     * Reconditions one profile after a nearby condenser-temperature change without retaining it outside the
     * current calculation. The profile's normalized thermal rise is transferred first, then the DWSIM-style
     * tridiagonal material/VLE and traffic refresh is applied at the target temperature. The coupled MESH solve
     * still owns the subsequent energy closure and fresh acceptance audit.
     */
    static ContinuedProfile continueFromProfile(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            V3DryMeshState acceptedProfile) {
        problem = Objects.requireNonNull(problem, "problem");
        thermo = Objects.requireNonNull(thermo, "thermo");
        workspace = Objects.requireNonNull(workspace, "workspace");
        acceptedProfile = Objects.requireNonNull(acceptedProfile, "acceptedProfile");
        if (acceptedProfile.nodeCount() != problem.topology().nodeCount()
                || acceptedProfile.componentCount() != problem.activeComponentBasis().componentCount()) {
            throw new IllegalArgumentException("V3 continuation profile does not match the target problem");
        }

        double[][] liquid = flows(acceptedProfile, true);
        double[][] vapor = flows(acceptedProfile, false);
        double[] transferredTemperatures = transferThermalProfile(problem, acceptedProfile);
        V3DryMeshState transferred = new V3DryMeshState(
                problem.topology(), acceptedProfile.componentCount(), liquid, vapor, transferredTemperatures);
        if (!problem.topology().hasVaporLiquidEquilibriumEquation(problem.topology().condenserNode())) {
            return new ContinuedProfile(transferred, "profile-transfer/single-phase-condenser");
        }
        try {
            int feedNode = problem.topology().feedTrayNumber();
            V3FlashResult feedFlash = thermo.flashTP(problem.input().feedTemperatureKelvin(),
                    problem.nodePressurePascal(feedNode), problem.input().feedComponentMolarFlowsMolPerSecond(), workspace);
            double feedFlow = problem.activeComponentBasis().totalFeedFlowMolPerSecond();
            double[][] kValues = phaseRatios(
                    problem, thermo, workspace, liquid, vapor, transferredTemperatures);
            ColdTrafficCandidate transferredTraffic = trafficCandidateFromProfile(
                    problem, kValues, feedFlow, feedFlash.vaporFraction(), liquid, vapor);
            if (transferredTraffic == null) return new ContinuedProfile(transferred, "profile-transfer/no-material-tear");
            ColdTrafficCandidate refreshed = refineCondenserTemperatureTraffic(
                    problem, thermo, workspace, kValues, feedFlow, feedFlash.vaporFraction(), transferredTraffic,
                    feedFlash.molarEnthalpyJoulesPerMol(), transferredTemperatures);
            boolean refreshedTraffic = refreshed != transferredTraffic;
            return new ContinuedProfile(
                    new V3DryMeshState(problem.topology(), acceptedProfile.componentCount(),
                            refreshed.liquidFlows(), refreshed.vaporFlows(), transferredTemperatures),
                    refreshedTraffic ? "profile-transfer/dwsim-material-vle-energy" : "profile-transfer/no-energy-traffic");
        } catch (V3ThermoException | IllegalArgumentException unavailableRefresh) {
            // A valid accepted profile is the safest continuation seed when the optional sequential refresh cannot
            // produce a physical tridiagonal traffic candidate at this parameter step.
            return new ContinuedProfile(transferred, "profile-transfer/thermo-fallback");
        }
    }

    private static V3DryMeshState phaseAwareTrafficSeed(
            V3ColumnProblem problem, V3ThermoModel thermo, V3ThermoWorkspace workspace, V3DryMeshState materialClosed) {
        double[][] liquid = flows(materialClosed, true);
        double[][] vapor = flows(materialClosed, false);
        for (int sweep = 0; sweep < PHASE_AWARE_SWEEPS; sweep++) {
            double[][] kValues = phaseRatios(problem, thermo, workspace, liquid, vapor, temperatures(materialClosed));
            for (int node = 0; node < vapor.length; node++) {
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
            double feedVaporFraction) {
        V3ColumnTopology topology = problem.topology();
        if (!topology.hasLiquidPhase(topology.condenserNode())) return materialClosed;
        double feedFlow = problem.activeComponentBasis().totalFeedFlowMolPerSecond();
        double[][] kValues = phaseRatios(problem, thermo, workspace, flows(materialClosed, true), flows(materialClosed, false),
                temperatures(materialClosed));
        ColdTrafficCandidate best = null;
        for (int liquidProductStep = 1; liquidProductStep < COLD_TRAFFIC_GRID_DIVISIONS; liquidProductStep++) {
            for (int vaporProductStep = 1;
                 liquidProductStep + vaporProductStep < COLD_TRAFFIC_GRID_DIVISIONS;
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
        ColdTrafficCandidate refreshed = refineTrafficCandidate(
                problem, thermo, workspace, kValues, feedFlow, feedVaporFraction, best, temperatures(materialClosed));
        return new V3DryMeshState(topology, materialClosed.componentCount(), refreshed.liquidFlows(), refreshed.vaporFlows(),
                temperatures(materialClosed));
    }

    /**
     * Total condensers have no condenser vapor unknown or VLE row. Their material preconditioner therefore starts
     * with V1=L0, solves the reduced tridiagonal component systems, and refreshes PR K-values only on the
     * vapor/liquid tray and reboiler nodes.
     */
    private static V3DryMeshState totalCondenserSequentialSeed(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            V3DryMeshState materialClosed,
            double feedVaporFraction) {
        V3ColumnTopology topology = problem.topology();
        if (topology.hasVaporPhase(topology.condenserNode()) || !topology.hasLiquidPhase(topology.condenserNode())) {
            throw new IllegalArgumentException("V3 total-condenser initializer requires a liquid-only condenser node");
        }
        double feedFlow = problem.activeComponentBasis().totalFeedFlowMolPerSecond();
        double[] temperatures = temperatures(materialClosed);
        double[][] kValues = phaseRatios(problem, thermo, workspace, flows(materialClosed, true), flows(materialClosed, false),
                temperatures);
        ColdTrafficCandidate best = null;
        for (int liquidProductStep = 1; liquidProductStep < COLD_TRAFFIC_GRID_DIVISIONS; liquidProductStep++) {
            ColdTrafficCandidate candidate = totalCondenserTrafficCandidate(
                    problem, kValues, feedFlow, feedVaporFraction,
                    liquidProductStep / (double) COLD_TRAFFIC_GRID_DIVISIONS);
            if (candidate != null && (best == null || candidate.sumRatesMismatch() < best.sumRatesMismatch())) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("V3 total-condenser initializer found no positive material traffic candidate");
        }
        ColdTrafficCandidate refreshed = refineTotalCondenserTrafficCandidate(
                problem, thermo, workspace, kValues, feedFlow, feedVaporFraction, best, temperatures);
        return new V3DryMeshState(topology, materialClosed.componentCount(), refreshed.liquidFlows(), refreshed.vaporFlows(),
                temperatures);
    }

    private static ColdTrafficCandidate refineTotalCondenserTrafficCandidate(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            double[][] kValues,
            double feedFlow,
            double feedVaporFraction,
            ColdTrafficCandidate initial,
            double[] temperatures) {
        ColdTrafficCandidate best = initial;
        double liquidProductFraction = initial.liquidProductFraction();
        for (int sweep = 0; sweep < MAXIMUM_COLD_TRAFFIC_SWEEPS; sweep++) {
            ColdTrafficCandidate candidate = totalCondenserTrafficCandidate(
                    problem, kValues, feedFlow, feedVaporFraction, liquidProductFraction);
            if (candidate == null) break;
            if (candidate.sumRatesMismatch() < best.sumRatesMismatch()) best = candidate;
            double maximumTemperatureChange = updateBubblePointTemperatures(
                    problem, thermo, workspace, candidate, temperatures);
            double actualLiquidProductFraction = (1.0 - organicRefluxFraction(problem))
                    * sum(candidate.liquidFlows()[problem.topology().condenserNode()]) / feedFlow;
            if (!(actualLiquidProductFraction > 0.0) || actualLiquidProductFraction >= 1.0) break;
            double trafficChange = Math.abs(actualLiquidProductFraction - liquidProductFraction);
            liquidProductFraction += COLD_TRAFFIC_DAMPING
                    * (actualLiquidProductFraction - liquidProductFraction);
            double[][] updatedK = phaseRatios(
                    problem, thermo, workspace, candidate.liquidFlows(), candidate.vaporFlows(), temperatures);
            double maximumLogKChange = 0.0;
            for (int node = 1; node < kValues.length; node++) {
                for (int component = 0; component < kValues[node].length; component++) {
                    maximumLogKChange = Math.max(maximumLogKChange,
                            Math.abs(Math.log(updatedK[node][component]) - Math.log(kValues[node][component])));
                    kValues[node][component] = Math.exp((1.0 - COLD_TRAFFIC_DAMPING) * Math.log(kValues[node][component])
                            + COLD_TRAFFIC_DAMPING * Math.log(updatedK[node][component]));
                }
            }
            if (trafficChange <= 1.0e-6 && maximumLogKChange <= 1.0e-5 && maximumTemperatureChange <= 1.0e-5) break;
        }
        return best;
    }

    private static ColdTrafficCandidate refineTrafficCandidate(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            double[][] kValues,
            double feedFlow,
            double feedVaporFraction,
            ColdTrafficCandidate initial,
            double[] temperatures) {
        V3ColumnTopology topology = problem.topology();
        ColdTrafficCandidate best = initial;
        double liquidProductFraction = initial.liquidProductFraction();
        double vaporProductFraction = initial.vaporProductFraction();
        for (int sweep = 0; sweep < MAXIMUM_COLD_TRAFFIC_SWEEPS; sweep++) {
            ColdTrafficCandidate candidate = coldTrafficCandidate(
                    problem, kValues, feedFlow, feedVaporFraction, liquidProductFraction, vaporProductFraction);
            if (candidate == null) break;
            if (candidate.sumRatesMismatch() < best.sumRatesMismatch()) best = candidate;
            double actualLiquidProductFraction = (1.0 - organicRefluxFraction(problem))
                    * sum(candidate.liquidFlows()[topology.condenserNode()]) / feedFlow;
            double actualVaporProductFraction = sum(candidate.vaporFlows()[topology.condenserNode()]) / feedFlow;
            if (!(actualLiquidProductFraction > 0.0) || !(actualVaporProductFraction > 0.0)
                    || actualLiquidProductFraction + actualVaporProductFraction >= 1.0) break;
            double trafficChange = Math.max(Math.abs(actualLiquidProductFraction - liquidProductFraction),
                    Math.abs(actualVaporProductFraction - vaporProductFraction));
            liquidProductFraction += COLD_TRAFFIC_DAMPING * (actualLiquidProductFraction - liquidProductFraction);
            vaporProductFraction += COLD_TRAFFIC_DAMPING * (actualVaporProductFraction - vaporProductFraction);
            double[][] updatedK = phaseRatios(
                    problem, thermo, workspace, candidate.liquidFlows(), candidate.vaporFlows(), temperatures);
            double maximumLogKChange = 0.0;
            for (int node = 0; node < kValues.length; node++) {
                for (int component = 0; component < kValues[node].length; component++) {
                    maximumLogKChange = Math.max(maximumLogKChange,
                            Math.abs(Math.log(updatedK[node][component]) - Math.log(kValues[node][component])));
                    kValues[node][component] = Math.exp((1.0 - COLD_TRAFFIC_DAMPING) * Math.log(kValues[node][component])
                            + COLD_TRAFFIC_DAMPING * Math.log(updatedK[node][component]));
                }
            }
            if (trafficChange <= 1.0e-6 && maximumLogKChange <= 1.0e-5) break;
        }
        return best;
    }

    /**
     * Wang-Henke-style energy/traffic tear for one parameter-continuation seed. Material closure is solved through
     * the existing tridiagonal systems; fresh stage enthalpies then provide a reboiler-to-condenser vapor-flow
     * update. The damped overhead-vapor target is fed back into the next material solve instead of being published.
     */
    private static ColdTrafficCandidate refineCondenserTemperatureTraffic(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            double[][] kValues,
            double feedFlow,
            double feedVaporFraction,
            ColdTrafficCandidate initial,
            double feedMolarEnthalpyJoulesPerMol,
            double[] temperatures) {
        ColdTrafficCandidate best = initial;
        double liquidProductFraction = initial.liquidProductFraction();
        ColdTrafficCandidate current = initial;
        for (int sweep = 0; sweep < MAXIMUM_CONDENSER_TEAR_SWEEPS; sweep++) {
            double maximumTemperatureChange = updateBubblePointTemperatures(
                    problem, thermo, workspace, current, temperatures);
            double[][] updatedK = phaseRatios(
                    problem, thermo, workspace, current.liquidFlows(), current.vaporFlows(), temperatures);
            EnergyProperties enthalpies = energyProperties(problem, thermo, workspace, current, temperatures);
            ColdTrafficCandidate candidate = selectDwsimEnergyTrafficCandidate(
                    problem, updatedK, feedFlow, feedVaporFraction, liquidProductFraction, enthalpies,
                    feedMolarEnthalpyJoulesPerMol);
            if (candidate == null) break;
            best = candidate;
            double actualLiquidProductFraction = (1.0 - organicRefluxFraction(problem))
                    * sum(candidate.liquidFlows()[problem.topology().condenserNode()]) / feedFlow;
            if (!(actualLiquidProductFraction > 0.0) || actualLiquidProductFraction >= 1.0) break;
            double trafficChange = Math.abs(actualLiquidProductFraction - liquidProductFraction);
            liquidProductFraction += CONDENSER_TEAR_DAMPING
                    * (actualLiquidProductFraction - liquidProductFraction);
            double maximumLogKChange = 0.0;
            for (int node = 0; node < updatedK.length; node++) {
                for (int component = 0; component < updatedK[node].length; component++) {
                    maximumLogKChange = Math.max(maximumLogKChange,
                            Math.abs(Math.log(updatedK[node][component]) - Math.log(kValues[node][component])));
                    kValues[node][component] = Math.exp((1.0 - CONDENSER_TEAR_DAMPING)
                            * Math.log(kValues[node][component]) + CONDENSER_TEAR_DAMPING
                            * Math.log(updatedK[node][component]));
                }
            }
            current = candidate;
            if (trafficChange <= 1.0e-6 && maximumLogKChange <= 1.0e-5 && maximumTemperatureChange <= 1.0e-5) break;
        }
        return best;
    }

    /** Solves the local bubble-point residual with the current phase compositions and a DWSIM-style capped update. */
    private static double updateBubblePointTemperatures(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            ColdTrafficCandidate candidate,
            double[] temperatures) {
        V3ColumnTopology topology = problem.topology();
        V3ActiveComponentBasis active = problem.activeComponentBasis();
        double[][] liquid = candidate.liquidFlows();
        double[][] vapor = candidate.vaporFlows();
        double maximumChange = 0.0;
        for (int node = 1; node <= topology.reboilerNode(); node++) {
            double temperature = temperatures[node];
            double residual = bubblePointResidual(problem, thermo, workspace, active, liquid[node], vapor[node],
                    node, temperature);
            double upperResidual = bubblePointResidual(problem, thermo, workspace, active, liquid[node], vapor[node],
                    node, temperature + BUBBLE_POINT_DIFFERENCE_KELVIN);
            double lowerResidual = bubblePointResidual(problem, thermo, workspace, active, liquid[node], vapor[node],
                    node, temperature - BUBBLE_POINT_DIFFERENCE_KELVIN);
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
     * Builds a material/VLE candidate from the same top-down energy traffic recurrence used by bubble-point column
     * solvers. The overhead-vapor product is the scalar tear: with the current enthalpy profile, it is chosen so the
     * tray recurrence and reboiler-duty equation predict the same reboiler vapor rate. Liquid traffic then follows
     * directly from cumulative material closure before the component tridiagonal systems are solved.
     */
    private static ColdTrafficCandidate dwsimEnergyTrafficCandidate(
            V3ColumnProblem problem,
            double[][] kValues,
            double feedFlow,
            double feedVaporFraction,
            double liquidProductFraction,
            EnergyProperties enthalpies,
            double feedMolarEnthalpyJoulesPerMol) {
        double liquidProduct = liquidProductFraction * feedFlow;
        if (!(liquidProduct > 0.0) || liquidProduct >= feedFlow) return null;
        if (enthalpies == null) return null;
        double probeVaporProduct = Math.max(feedFlow * 0.1, 1.0e-6);
        double zeroResidual = reboilerTrafficMismatch(
                problem, enthalpies, feedFlow, liquidProduct, 0.0, feedMolarEnthalpyJoulesPerMol);
        double probeResidual = reboilerTrafficMismatch(
                problem, enthalpies, feedFlow, liquidProduct, probeVaporProduct, feedMolarEnthalpyJoulesPerMol);
        double slope = (probeResidual - zeroResidual) / probeVaporProduct;
        if (!Double.isFinite(zeroResidual) || !Double.isFinite(slope) || Math.abs(slope) <= 1.0e-12) return null;
        double vaporProduct = -zeroResidual / slope;
        if (!(vaporProduct > 0.0) || liquidProduct + vaporProduct >= feedFlow) return null;
        TrafficTotals traffic = dwsimEnergyTrafficTotals(
                problem, enthalpies, feedFlow, liquidProduct, vaporProduct, feedMolarEnthalpyJoulesPerMol);
        if (traffic == null || !positiveFiniteTraffic(traffic)) return null;
        return coldTrafficCandidate(problem, kValues, feedFlow, feedVaporFraction,
                traffic.liquidTotals(), traffic.vaporTotals(), liquidProductFraction, vaporProduct / feedFlow);
    }

    /**
     * A temperature change can invalidate the previous product split before the energy tear is evaluated. Search a
     * small deterministic liquid-product grid for a finite traffic candidate, retaining the best material closure as
     * a preconditioner only. The rigorous MESH solve still determines the final product split.
     */
    private static ColdTrafficCandidate selectDwsimEnergyTrafficCandidate(
            V3ColumnProblem problem,
            double[][] kValues,
            double feedFlow,
            double feedVaporFraction,
            double preferredLiquidProductFraction,
            EnergyProperties enthalpies,
            double feedMolarEnthalpyJoulesPerMol) {
        ColdTrafficCandidate best = dwsimEnergyTrafficCandidate(
                problem, kValues, feedFlow, feedVaporFraction, preferredLiquidProductFraction, enthalpies,
                feedMolarEnthalpyJoulesPerMol);
        for (int step = 1; step < 32; step++) {
            double liquidProductFraction = step / 32.0;
            ColdTrafficCandidate candidate = dwsimEnergyTrafficCandidate(
                    problem, kValues, feedFlow, feedVaporFraction, liquidProductFraction, enthalpies,
                    feedMolarEnthalpyJoulesPerMol);
            if (candidate != null && (best == null || candidate.sumRatesMismatch() < best.sumRatesMismatch())) {
                best = candidate;
            }
        }
        return best;
    }

    private static EnergyProperties energyProperties(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            ColdTrafficCandidate reference,
            double[] temperatures) {
        V3ColumnTopology topology = problem.topology();
        int nodes = topology.nodeCount();
        double[] liquid = new double[nodes];
        double[] vapor = new double[nodes];
        V3ActiveComponentBasis active = problem.activeComponentBasis();
        for (int node = 0; node < nodes; node++) {
            double[] liquidFlows = reference.liquidFlows()[node];
            double[] vaporFlows = reference.vaporFlows()[node];
            if (!(sum(liquidFlows) > 0.0) || !(sum(vaporFlows) > 0.0)) return null;
            double pressure = problem.nodePressurePascal(node);
            try {
                liquid[node] = thermo.molarEnthalpy(
                        temperatures[node], pressure, publicComposition(active, liquidFlows), V3Phase.LIQUID, workspace);
                vapor[node] = thermo.molarEnthalpy(
                        temperatures[node], pressure, publicComposition(active, vaporFlows), V3Phase.VAPOR, workspace);
            } catch (V3ThermoException | IllegalArgumentException unavailable) {
                return null;
            }
            if (!Double.isFinite(liquid[node]) || !Double.isFinite(vapor[node])) return null;
        }
        return new EnergyProperties(liquid, vapor);
    }

    private static double reboilerTrafficMismatch(
            V3ColumnProblem problem,
            EnergyProperties enthalpies,
            double feedFlow,
            double liquidProduct,
            double vaporProduct,
            double feedMolarEnthalpyJoulesPerMol) {
        TrafficTotals traffic = dwsimEnergyTrafficTotals(
                problem, enthalpies, feedFlow, liquidProduct, vaporProduct, feedMolarEnthalpyJoulesPerMol);
        if (traffic == null) return Double.NaN;
        int reboiler = problem.topology().reboilerNode();
        return traffic.trayPredictedReboilerVapor() - traffic.vaporTotals()[reboiler];
    }

    private static TrafficTotals dwsimEnergyTrafficTotals(
            V3ColumnProblem problem,
            EnergyProperties enthalpies,
            double feedFlow,
            double liquidProduct,
            double vaporProduct,
            double feedMolarEnthalpyJoulesPerMol) {
        V3ColumnTopology topology = problem.topology();
        int reboiler = topology.reboilerNode();
        int trayCount = topology.trayCount();
        double bottoms = feedFlow - liquidProduct - vaporProduct;
        if (!Double.isFinite(bottoms)) return null;
        double[] vapor = new double[reboiler + 1];
        double[] liquid = new double[reboiler + 1];
        double refluxRatio = specification(problem, V3ColumnSpecification.OrganicRefluxRatio.class).ratio();
        vapor[0] = vaporProduct;
        liquid[0] = (1.0 + refluxRatio) * liquidProduct;
        vapor[1] = liquid[0] + vaporProduct;
        double feedBefore = 0.0;
        for (int tray = 1; tray <= trayCount; tray++) {
            double feedAtTray = tray == topology.feedTrayNumber() ? feedFlow : 0.0;
            double alpha = enthalpies.liquid()[tray - 1] - enthalpies.vapor()[tray];
            double beta = enthalpies.vapor()[tray + 1] - enthalpies.liquid()[tray];
            double gamma = (feedBefore - liquidProduct - vaporProduct)
                    * (enthalpies.liquid()[tray] - enthalpies.liquid()[tray - 1])
                    + feedAtTray * (enthalpies.liquid()[tray] - feedMolarEnthalpyJoulesPerMol);
            if (!Double.isFinite(alpha) || !Double.isFinite(beta) || !Double.isFinite(gamma)
                    || Math.abs(beta) <= 1.0e-12) return null;
            double nextVapor = (gamma - alpha * vapor[tray]) / beta;
            if (tray < trayCount) vapor[tray + 1] = nextVapor;
            else {
                double reboilerDuty = specification(problem, V3ColumnSpecification.ReboilerDuty.class).watts();
                double reboilerVapor = (bottoms * (enthalpies.liquid()[reboiler]
                        - enthalpies.liquid()[trayCount]) - reboilerDuty)
                        / (enthalpies.liquid()[trayCount] - enthalpies.vapor()[reboiler]);
                if (!Double.isFinite(nextVapor) || !Double.isFinite(reboilerVapor)) return null;
                vapor[reboiler] = reboilerVapor;
                double cumulativeFeed = 0.0;
                for (int liquidTray = 1; liquidTray <= trayCount; liquidTray++) {
                    if (liquidTray == topology.feedTrayNumber()) cumulativeFeed += feedFlow;
                    liquid[liquidTray] = vapor[liquidTray + 1] + cumulativeFeed - liquidProduct - vaporProduct;
                    if (!Double.isFinite(liquid[liquidTray])) return null;
                }
                liquid[reboiler] = bottoms;
                if (!Double.isFinite(liquid[reboiler])) return null;
                return new TrafficTotals(liquid, vapor, nextVapor);
            }
            feedBefore += feedAtTray;
        }
        throw new IllegalStateException("V3 energy traffic recurrence did not reach the reboiler");
    }

    private static ColdTrafficCandidate trafficCandidateFromProfile(
            V3ColumnProblem problem,
            double[][] kValues,
            double feedFlow,
            double feedVaporFraction,
            double[][] liquid,
            double[][] vapor) {
        int condenser = problem.topology().condenserNode();
        double liquidProductFraction = (1.0 - organicRefluxFraction(problem)) * sum(liquid[condenser]) / feedFlow;
        double vaporProductFraction = sum(vapor[condenser]) / feedFlow;
        if (!Double.isFinite(liquidProductFraction) || !Double.isFinite(vaporProductFraction)
                || liquidProductFraction <= 0.0 || vaporProductFraction <= 0.0
                || liquidProductFraction + vaporProductFraction >= 1.0) {
            return null;
        }
        return coldTrafficCandidate(
                problem, kValues, feedFlow, feedVaporFraction, liquidProductFraction, vaporProductFraction);
    }

    private static ColdTrafficCandidate totalCondenserTrafficCandidate(
            V3ColumnProblem problem,
            double[][] kValues,
            double feedFlow,
            double feedVaporFraction,
            double liquidProductFraction) {
        V3ColumnTopology topology = problem.topology();
        int nodes = topology.nodeCount();
        int components = problem.activeComponentBasis().componentCount();
        double[] liquidTotals = new double[nodes];
        double[] vaporTotals = new double[nodes];
        if (!buildTotalCondenserTrafficTotals(
                problem, feedFlow, feedVaporFraction, liquidProductFraction, liquidTotals, vaporTotals)) {
            return null;
        }
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        for (int component = 0; component < components; component++) {
            double[] componentPhaseRatios = new double[nodes];
            componentPhaseRatios[0] = 1.0;
            for (int node = 1; node < nodes; node++) {
                componentPhaseRatios[node] = boundedRatio(
                        kValues[node][component] * vaporTotals[node] / liquidTotals[node]);
            }
            double[] componentLiquid;
            try {
                componentLiquid = solveTotalCondenserComponentMaterialBalance(
                        problem, component, componentPhaseRatios, organicRefluxFraction(problem));
            } catch (IllegalArgumentException singularOrNegative) {
                return null;
            }
            for (int node = 0; node < nodes; node++) {
                liquid[node][component] = componentLiquid[node];
                vapor[node][component] = node == 0 ? 0.0 : componentPhaseRatios[node] * componentLiquid[node];
            }
        }
        double mismatch = sumRatesMismatch(problem, liquid, vapor, liquidTotals, vaporTotals);
        return Double.isFinite(mismatch)
                ? new ColdTrafficCandidate(liquid, vapor, mismatch, liquidProductFraction, 0.0) : null;
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
        V3ColumnTopology topology = problem.topology();
        int nodes = topology.nodeCount();
        int components = problem.activeComponentBasis().componentCount();
        if (liquidTotals.length != nodes || vaporTotals.length != nodes) {
            throw new IllegalArgumentException("V3 sequential material traffic dimensions disagree");
        }
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        for (int component = 0; component < components; component++) {
            double[] componentPhaseRatios = new double[nodes];
            for (int node = 0; node < nodes; node++) {
                componentPhaseRatios[node] = boundedRatio(
                        kValues[node][component] * vaporTotals[node] / liquidTotals[node]);
            }
            double[] componentLiquid;
            try {
                componentLiquid = solveComponentMaterialBalance(
                        problem, component, componentPhaseRatios, organicRefluxFraction(problem));
            } catch (IllegalArgumentException singularOrNegative) {
                return null;
            }
            for (int node = 0; node < nodes; node++) {
                liquid[node][component] = componentLiquid[node];
                vapor[node][component] = componentPhaseRatios[node] * componentLiquid[node];
            }
        }
        double mismatch = sumRatesMismatch(problem, liquid, vapor, liquidTotals, vaporTotals);
        return Double.isFinite(mismatch)
                ? new ColdTrafficCandidate(liquid, vapor, mismatch, liquidProductFraction, vaporProductFraction) : null;
    }

    private static boolean positiveFiniteTraffic(TrafficTotals traffic) {
        double[] liquid = traffic.liquidTotals();
        double[] vapor = traffic.vaporTotals();
        if (liquid.length != vapor.length) return false;
        for (int node = 0; node < liquid.length; node++) {
            if (!(liquid[node] > 0.0) || !(vapor[node] > 0.0)
                    || !Double.isFinite(liquid[node]) || !Double.isFinite(vapor[node])) return false;
        }
        return true;
    }

    private static boolean buildTotalCondenserTrafficTotals(
            V3ColumnProblem problem,
            double feedFlow,
            double feedVaporFraction,
            double liquidProductFraction,
            double[] liquidTotals,
            double[] vaporTotals) {
        V3ColumnTopology topology = problem.topology();
        if (topology.hasVaporPhase(topology.condenserNode())) return false;
        double liquidProduct = liquidProductFraction * feedFlow;
        double bottoms = feedFlow - liquidProduct;
        double refluxFraction = organicRefluxFraction(problem);
        if (!(liquidProduct > 0.0) || !(bottoms > 0.0) || refluxFraction >= 1.0) return false;
        double condensate = liquidProduct / (1.0 - refluxFraction);
        if (!Double.isFinite(condensate) || condensate <= 0.0) return false;
        liquidTotals[0] = condensate;
        vaporTotals[0] = 0.0;
        double incomingLiquid = refluxFraction * condensate;
        double upwardVapor = condensate;
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
        if (!(liquidProduct > 0.0) || !(overheadVapor > 0.0) || !(bottoms > 0.0) || refluxFraction >= 1.0) return false;
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
            V3ColumnProblem problem,
            double[][] liquid,
            double[][] vapor,
            double[] liquidTotals,
            double[] vaporTotals) {
        double mismatch = 0.0;
        for (int node = 0; node < liquid.length; node++) {
            double liquidSum = 0.0;
            double vaporSum = 0.0;
            for (int component = 0; component < liquid[node].length; component++) {
                liquidSum += liquid[node][component];
                vaporSum += vapor[node][component];
            }
            if (problem.topology().hasLiquidPhase(node)) {
                if (!(liquidSum > 0.0)) return Double.POSITIVE_INFINITY;
                mismatch = Math.max(mismatch, Math.abs(Math.log(liquidSum / liquidTotals[node])));
            } else if (liquidSum != 0.0) {
                return Double.POSITIVE_INFINITY;
            }
            if (problem.topology().hasVaporPhase(node)) {
                if (!(vaporSum > 0.0)) return Double.POSITIVE_INFINITY;
                mismatch = Math.max(mismatch, Math.abs(Math.log(vaporSum / vaporTotals[node])));
            } else if (vaporSum != 0.0) {
                return Double.POSITIVE_INFINITY;
            }
        }
        return mismatch;
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    private static double[][] phaseRatios(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            double[][] liquid,
            double[][] vapor,
            double[] temperatures) {
        V3ActiveComponentBasis active = problem.activeComponentBasis();
        double[][] ratios = new double[liquid.length][active.componentCount()];
        for (int node = 0; node < liquid.length; node++) {
            if (!problem.topology().hasVaporLiquidEquilibriumEquation(node)) {
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

    private static double[] solveTotalCondenserComponentMaterialBalance(
            V3ColumnProblem problem, int component, double[] phaseRatios, double refluxFraction) {
        V3ColumnTopology topology = problem.topology();
        int reboiler = topology.reboilerNode();
        double[] lower = new double[reboiler + 1];
        double[] diagonal = new double[reboiler + 1];
        double[] upper = new double[reboiler + 1];
        double[] rightHandSide = new double[reboiler + 1];

        diagonal[0] = -1.0;
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

    private static double[][] flows(V3DryMeshState state, boolean liquid) {
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
            if (!Double.isFinite(flow) || flow <= 0.0) {
                throw new IllegalArgumentException("V3 sequential material preconditioner generated a nonpositive component flow");
            }
            composition[active.publicIndex(component)] = flow / total;
        }
        return composition;
    }

    private static double[] temperatures(V3DryMeshState state) {
        double[] values = new double[state.nodeCount()];
        for (int node = 0; node < values.length; node++) values[node] = state.temperatureKelvin(node);
        return values;
    }

    private static double[] transferThermalProfile(V3ColumnProblem target, V3DryMeshState source) {
        double targetCondenserTemperature = specification(
                target, V3ColumnSpecification.CondenserOutletTemperature.class).kelvin();
        double sourceCondenserTemperature = source.temperatureKelvin(target.topology().condenserNode());
        double sourceSpan = target.input().feedTemperatureKelvin() - sourceCondenserTemperature;
        double targetSpan = target.input().feedTemperatureKelvin() - targetCondenserTemperature;
        double[] temperatures = new double[source.nodeCount()];
        for (int node = 0; node < temperatures.length; node++) {
            double normalizedRise = Math.abs(sourceSpan) <= 1.0e-9
                    ? node / (temperatures.length - 1.0)
                    : (source.temperatureKelvin(node) - sourceCondenserTemperature) / sourceSpan;
            double candidate = targetCondenserTemperature + normalizedRise * targetSpan;
            temperatures[node] = Double.isFinite(candidate) && candidate > 0.0
                    ? candidate
                    : targetCondenserTemperature + targetSpan * node / (temperatures.length - 1.0);
        }
        temperatures[target.topology().condenserNode()] = targetCondenserTemperature;
        return temperatures;
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

    record ContinuedProfile(V3DryMeshState state, String pathMarker) {
        ContinuedProfile {
            state = Objects.requireNonNull(state, "state");
            if (pathMarker == null || pathMarker.isBlank()) {
                throw new IllegalArgumentException("V3 continuation path marker is blank");
            }
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

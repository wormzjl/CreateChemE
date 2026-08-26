package com.wormzjl.createcheme.science.column.nextgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The bounded scalar water/steam active set prescribed by the next-column contract.
 *
 * <p>Hydrocarbons are supplied as the already-solved dry-vapor rate {@code G}; water never enters the
 * 16-component Peng-Robinson matrix. For a fixed wet mask this class substitutes the saturation steam rate and
 * solves the Section 7.4 condenser/tray/reboiler water balances with one Thomas solve. Only the mask is iterated.
 * The caller owns a {@link Workspace} for repeated inner-column use; returned results are defensive snapshots.
 */
public final class WaterActiveSet {
    public static final int MAX_WATER_MASK_PASSES = 8;
    private static final double THOMAS_BACKWARD_ERROR_LIMIT = 1.0e-12;
    private static final double HYSTERESIS_PRESSURE_RELATIVE = 1.0e-6;
    private static final double COMPLEMENTARITY_PRESSURE_RELATIVE = 1.0e-8;
    private static final int MAX_TRANSITIONS_REPORTED = 32;

    private WaterActiveSet() {}

    /**
     * Solves a bounded active-set pass. Node numbering is {@code 0=condenser, 1..S=trays, N=reboiler}; water
     * external feeds are allowed only at tray nodes. The supplied mask is a warm-start hint and is never mutated.
     */
    public static Outcome solve(Input input, Workspace workspace) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(workspace, "workspace");
        int nodes = input.nodeCount();
        if (workspace.nodes != nodes) {
            throw new IllegalArgumentException("Water active-set workspace does not match node count");
        }
        input.copyInto(workspace);
        List<String> transitions = new ArrayList<>();
        double referenceFlow = Math.max(1.0, sumPositive(workspace.waterFeed));
        double flowAbsoluteTolerance = 1.0e-12 * referenceFlow;

        for (int pass = 1; pass <= MAX_WATER_MASK_PASSES; pass++) {
            Failure failure = prepareFixedMask(workspace, pass, transitions, flowAbsoluteTolerance);
            if (failure != null) return failure;

            double backwardError;
            int structuralRepairs = 0;
            while (true) {
                try {
                    backwardError = ThomasTridiagonalSolver.solve(
                            workspace.lower, workspace.diagonal, workspace.upper, workspace.rightHandSide,
                            workspace.scalar, workspace.cPrime, workspace.dPrime);
                    break;
                } catch (IllegalStateException exception) {
                    int activatedWetNode = activateDownstreamWetNodeForStructuralRepair(workspace);
                    if (activatedWetNode < 0 || ++structuralRepairs > nodes) {
                        return failure(FailureCode.TRIDIAGONAL_BREAKDOWN, exception.getMessage(), pass, transitions,
                                workspace, Double.NaN, Double.NaN, Double.NaN);
                    }
                    addTransition(transitions, "pass=" + pass + ",node=" + activatedWetNode
                            + ",dry->wet(structural-repair)");
                    failure = prepareFixedMask(workspace, pass, transitions, flowAbsoluteTolerance);
                    if (failure != null) return failure;
                }
            }
            if (!Double.isFinite(backwardError) || backwardError > THOMAS_BACKWARD_ERROR_LIMIT) {
                return failure(FailureCode.TRIDIAGONAL_BACKWARD_ERROR,
                        "Water Thomas backward error exceeds the declared limit", pass, transitions, workspace,
                        backwardError, Double.NaN, Double.NaN);
            }
            expandPhaseRates(workspace);
            failure = validateNonnegativeRates(workspace, pass, transitions, flowAbsoluteTolerance, backwardError);
            if (failure != null) return failure;

            int firstViolation = selectMaskViolations(workspace, flowAbsoluteTolerance);
            if (firstViolation < 0) {
                return finalAudit(workspace, pass, transitions, flowAbsoluteTolerance, backwardError);
            }
            for (int node = 0; node < nodes; node++) {
                if (workspace.violations[node] == MaskViolation.PURE_STEAM_SUPERSATURATED) {
                    return failure(FailureCode.PURE_STEAM_SATURATION_REQUIRES_ENERGY,
                            "A pure-steam node is supersaturated at its trial temperature and must be resolved by the coupled energy branch",
                            pass, transitions, workspace, backwardError, Double.NaN, Double.NaN);
                }
            }
            MaskViolation violation = workspace.violations[firstViolation];
            boolean nextWet = violation == MaskViolation.DRY_SUPERSATURATED;
            workspace.wetMask[firstViolation] = nextWet;
            addTransition(transitions, "pass=" + pass + ",node=" + firstViolation + ","
                    + (nextWet ? "dry->wet" : "wet->dry"));
        }
        return failure(FailureCode.WATER_ACTIVE_SET_FAILURE,
                "Water wet/dry mask did not settle within " + MAX_WATER_MASK_PASSES + " passes", MAX_WATER_MASK_PASSES,
                transitions, workspace, workspace.maximumBackwardError, workspace.maximumBalanceResidual,
                workspace.maximumComplementarityResidual);
    }

    private static Failure prepareFixedMask(
            Workspace w, int pass, List<String> transitions, double flowAbsoluteTolerance) {
        Arrays.fill(w.lower, 0.0);
        Arrays.fill(w.diagonal, 0.0);
        Arrays.fill(w.upper, 0.0);
        Arrays.fill(w.rightHandSide, 0.0);
        for (int node = 0; node < w.nodes; node++) {
            double temperature = w.temperatureKelvin[node];
            double pressure = w.totalPressurePascal[node];
            if (temperature >= If97Water.CRITICAL_TEMPERATURE_KELVIN) {
                // Region 4 is unavailable above critical temperature. A dry supercritical-steam node is still
                // representable; only the aqueous saturation branch must be rejected.
                w.saturationPressurePascal[node] = Double.NaN;
            } else {
                try {
                    w.saturationPressurePascal[node] = If97Water.saturationPressurePascal(temperature);
                } catch (IllegalArgumentException exception) {
                    return failure(FailureCode.WATER_PROPERTY_FAILURE, exception.getMessage(), pass, transitions, w,
                            Double.NaN, Double.NaN, Double.NaN);
                }
            }
            double dryHydrocarbonVapor = w.dryHydrocarbonVapor[node];
            if (w.wetMask[node]) {
                if (dryHydrocarbonVapor <= flowAbsoluteTolerance) {
                    return failure(FailureCode.PURE_STEAM_SATURATION_REQUIRES_ENERGY,
                            "A wet pure-steam node requires the coupled energy branch; scalar saturation substitution is undefined",
                            pass, transitions, w, Double.NaN, Double.NaN, Double.NaN);
                }
                double saturation = w.saturationPressurePascal[node];
                if (!(saturation > 0.0) || !(saturation < pressure)) {
                    return failure(FailureCode.INVALID_WET_SATURATION_BRANCH,
                            "Aqueous water cannot coexist with hydrocarbon vapor when Psat is not below total pressure",
                            pass, transitions, w, Double.NaN, Double.NaN, Double.NaN);
                }
                w.saturatedSteamRate[node] = dryHydrocarbonVapor * saturation / (pressure - saturation);
            } else {
                w.saturatedSteamRate[node] = 0.0;
            }
        }

        // condenser: w1 = w0 + a0
        w.diagonal[0] = -1.0;
        w.upper[0] = dryCoefficient(w.wetMask[1]);
        w.rightHandSide[0] = wetConstant(w, 0) - wetConstant(w, 1);

        // tray 1: w2 + F1 = w1 + a1
        w.diagonal[1] = -1.0;
        w.upper[1] = dryCoefficient(w.wetMask[2]);
        w.rightHandSide[1] = wetConstant(w, 1) - wetConstant(w, 2) - w.waterFeed[1];

        // trays 2..S: a(s-1) + w(s+1) + Fs = as + ws
        int reboiler = w.nodes - 1;
        for (int stage = 2; stage < reboiler; stage++) {
            w.lower[stage] = w.wetMask[stage - 1] ? 1.0 : 0.0;
            w.diagonal[stage] = -1.0;
            w.upper[stage] = dryCoefficient(w.wetMask[stage + 1]);
            w.rightHandSide[stage] = wetConstant(w, stage) - wetConstant(w, stage + 1) - w.waterFeed[stage];
        }

        // reboiler: aS = aN + wN
        w.lower[reboiler] = w.wetMask[reboiler - 1] ? 1.0 : 0.0;
        w.diagonal[reboiler] = -1.0;
        w.rightHandSide[reboiler] = wetConstant(w, reboiler);
        return null;
    }

    private static void expandPhaseRates(Workspace w) {
        for (int node = 0; node < w.nodes; node++) {
            if (w.wetMask[node]) {
                w.waterVapor[node] = w.saturatedSteamRate[node];
                w.aqueousLiquid[node] = w.scalar[node];
            } else {
                w.waterVapor[node] = w.scalar[node];
                w.aqueousLiquid[node] = 0.0;
            }
        }
    }

    private static Failure validateNonnegativeRates(
            Workspace w, int pass, List<String> transitions, double flowAbsoluteTolerance, double backwardError) {
        for (int node = 0; node < w.nodes; node++) {
            if (!Double.isFinite(w.waterVapor[node]) || !Double.isFinite(w.aqueousLiquid[node])
                    || w.waterVapor[node] < -flowAbsoluteTolerance
                    // A negative wet aqueous trial is the active-set release condition and must reach the
                    // top-to-bottom mask selector below. A dry node has identically zero aqueous flow.
                    || (!w.wetMask[node] && w.aqueousLiquid[node] < -flowAbsoluteTolerance)) {
                return failure(FailureCode.NEGATIVE_WATER_PHASE_FLOW,
                        "Water balance produced a negative or non-finite phase flow at node " + node,
                        pass, transitions, w, backwardError, Double.NaN, Double.NaN);
            }
        }
        return null;
    }

    /** Applies hysteresis only to mask selection; {@link #finalAudit} uses the strict complementarity tolerance. */
    private static int selectMaskViolations(Workspace w, double flowAbsoluteTolerance) {
        Arrays.fill(w.violations, MaskViolation.NONE);
        int first = -1;
        int firstNegativeAqueous = -1;
        for (int node = 0; node < w.nodes; node++) {
            double pressure = w.totalPressurePascal[node];
            double saturation = w.saturationPressurePascal[node];
            double hydrocarbon = w.dryHydrocarbonVapor[node];
            double steam = w.waterVapor[node];
            double partialPressure = waterPartialPressure(pressure, hydrocarbon, steam, flowAbsoluteTolerance);
            w.waterPartialPressurePascal[node] = partialPressure;
            w.hydrocarbonPartialPressurePascal[node] = partialPressure < 0.0 ? pressure : pressure - partialPressure;

            if (w.wetMask[node]) {
                if (w.aqueousLiquid[node] < -flowAbsoluteTolerance) {
                    w.violations[node] = MaskViolation.WET_NEGATIVE_AQUEOUS;
                    if (firstNegativeAqueous < 0) firstNegativeAqueous = node;
                }
            } else if (Double.isFinite(saturation) && steam > flowAbsoluteTolerance && partialPressure >= 0.0
                    && partialPressure > saturation + HYSTERESIS_PRESSURE_RELATIVE * pressure) {
                if (hydrocarbon > flowAbsoluteTolerance && saturation > 0.0 && saturation < pressure) {
                    w.violations[node] = MaskViolation.DRY_SUPERSATURATED;
                } else if (hydrocarbon <= flowAbsoluteTolerance && saturation < pressure) {
                    w.violations[node] = MaskViolation.PURE_STEAM_SUPERSATURATED;
                }
            }
            if (w.violations[node] != MaskViolation.NONE && first < 0) first = node;
        }
        if (firstNegativeAqueous >= 0) return firstNegativeAqueous;
        return first;
    }

    private static Outcome finalAudit(
            Workspace w, int pass, List<String> transitions, double flowAbsoluteTolerance, double backwardError) {
        double maximumBalance = 0.0;
        double maximumComplementarity = 0.0;
        for (int node = 0; node < w.nodes; node++) {
            double balance = waterBalanceResidual(w, node);
            maximumBalance = Math.max(maximumBalance, Math.abs(balance));
            double balanceLimit = flowAbsoluteTolerance + 1.0e-9 * waterBalanceScale(w, node);
            if (Math.abs(balance) > balanceLimit) {
                return failure(FailureCode.WATER_BALANCE_FAILURE, "Water balance does not close at node " + node,
                        pass, transitions, w, backwardError, maximumBalance, maximumComplementarity);
            }

            double partialPressure = waterPartialPressure(w.totalPressurePascal[node], w.dryHydrocarbonVapor[node],
                    w.waterVapor[node], flowAbsoluteTolerance);
            w.waterPartialPressurePascal[node] = partialPressure;
            w.hydrocarbonPartialPressurePascal[node] = partialPressure < 0.0
                    ? w.totalPressurePascal[node] : w.totalPressurePascal[node] - partialPressure;
            if (!Double.isFinite(w.saturationPressurePascal[node])) {
                if (w.aqueousLiquid[node] > flowAbsoluteTolerance) {
                    return failure(FailureCode.INVALID_WET_SATURATION_BRANCH,
                            "Aqueous water is unavailable at or above the water critical temperature", pass,
                            transitions, w, backwardError, maximumBalance, maximumComplementarity);
                }
                continue;
            }
            double saturationGap = partialPressure < 0.0 ? w.saturationPressurePascal[node]
                    : w.saturationPressurePascal[node] - partialPressure;
            double pressureTolerance = COMPLEMENTARITY_PRESSURE_RELATIVE * w.totalPressurePascal[node];
            double complementarity = Math.abs(w.aqueousLiquid[node] * saturationGap);
            maximumComplementarity = Math.max(maximumComplementarity, complementarity);
            if (w.aqueousLiquid[node] < -flowAbsoluteTolerance || saturationGap < -pressureTolerance
                    || complementarity > flowAbsoluteTolerance * pressureTolerance) {
                return failure(FailureCode.WATER_COMPLEMENTARITY_FAILURE,
                        "Water complementarity does not close at node " + node, pass, transitions, w,
                        backwardError, maximumBalance, maximumComplementarity);
            }
            if (w.hydrocarbonPartialPressurePascal[node] < -pressureTolerance) {
                return failure(FailureCode.INVALID_WET_SATURATION_BRANCH,
                        "Water partial pressure leaves a negative hydrocarbon partial pressure at node " + node,
                        pass, transitions, w, backwardError, maximumBalance, maximumComplementarity);
            }
        }
        w.maximumBackwardError = backwardError;
        w.maximumBalanceResidual = maximumBalance;
        w.maximumComplementarityResidual = maximumComplementarity;
        return new Result(w.waterVapor, w.aqueousLiquid, w.wetMask, w.saturationPressurePascal,
                w.waterPartialPressurePascal, w.hydrocarbonPartialPressurePascal,
                new Diagnostics(pass, transitions, backwardError, maximumBalance, maximumComplementarity));
    }

    private static double waterBalanceResidual(Workspace w, int node) {
        int reboiler = w.nodes - 1;
        if (node == 0) return w.waterVapor[1] - w.waterVapor[0] - w.aqueousLiquid[0];
        if (node == 1) return w.waterVapor[2] + w.waterFeed[1] - w.waterVapor[1] - w.aqueousLiquid[1];
        if (node < reboiler) return w.aqueousLiquid[node - 1] + w.waterVapor[node + 1] + w.waterFeed[node]
                - w.aqueousLiquid[node] - w.waterVapor[node];
        return w.aqueousLiquid[reboiler - 1] - w.aqueousLiquid[reboiler] - w.waterVapor[reboiler];
    }

    private static double waterBalanceScale(Workspace w, int node) {
        int reboiler = w.nodes - 1;
        if (node == 0) return Math.abs(w.waterVapor[1]) + Math.abs(w.waterVapor[0]) + Math.abs(w.aqueousLiquid[0]);
        if (node == 1) return Math.abs(w.waterVapor[2]) + Math.abs(w.waterFeed[1]) + Math.abs(w.waterVapor[1])
                + Math.abs(w.aqueousLiquid[1]);
        if (node < reboiler) return Math.abs(w.aqueousLiquid[node - 1]) + Math.abs(w.waterVapor[node + 1])
                + Math.abs(w.waterFeed[node]) + Math.abs(w.aqueousLiquid[node]) + Math.abs(w.waterVapor[node]);
        return Math.abs(w.aqueousLiquid[reboiler - 1]) + Math.abs(w.aqueousLiquid[reboiler])
                + Math.abs(w.waterVapor[reboiler]);
    }

    private static double waterPartialPressure(double pressure, double hydrocarbon, double steam, double flowTolerance) {
        double totalVapor = hydrocarbon + steam;
        if (totalVapor <= flowTolerance) return -1.0; // vapor composition is absent by contract.
        if (steam <= flowTolerance) return 0.0;
        return pressure * steam / totalVapor;
    }

    private static double wetConstant(Workspace w, int node) {
        return w.wetMask[node] ? w.saturatedSteamRate[node] : 0.0;
    }

    private static double dryCoefficient(boolean wet) {
        return wet ? 0.0 : 1.0;
    }

    /**
     * A wet mask can be algebraically singular when a saturated upstream node has no corresponding downstream
     * aqueous/steam branch. Complete that contiguous branch by activating its next downstream node and retry the
     * same scalar Thomas rows. This is an active-set degeneracy, not a reason to use a dense or stage-flash fallback.
     */
    private static int activateDownstreamWetNodeForStructuralRepair(Workspace w) {
        for (int node = 0; node + 1 < w.nodes; node++) {
            if (w.wetMask[node] && !w.wetMask[node + 1]) {
                w.wetMask[node + 1] = true;
                return node + 1;
            }
        }
        return -1;
    }

    private static double sumPositive(double[] values) {
        double total = 0.0;
        for (double value : values) total += Math.max(0.0, value);
        return total;
    }

    private static void addTransition(List<String> transitions, String transition) {
        if (transitions.size() < MAX_TRANSITIONS_REPORTED) transitions.add(transition);
    }

    private static Failure failure(
            FailureCode code, String detail, int passes, List<String> transitions, Workspace w,
            double backwardError, double balanceResidual, double complementarityResidual) {
        return new Failure(code, detail, new Diagnostics(passes, transitions, backwardError, balanceResidual,
                complementarityResidual));
    }

    public sealed interface Outcome permits Result, Failure {}

    /** Immutable, accepted phase state. All water arrays use the canonical node axis. */
    public record Result(
            double[] waterVaporMolPerSecond,
            double[] aqueousLiquidMolPerSecond,
            boolean[] wetMask,
            double[] saturationPressurePascal,
            double[] waterPartialPressurePascal,
            double[] hydrocarbonPartialPressurePascal,
            Diagnostics diagnostics) implements Outcome {
        public Result {
            waterVaporMolPerSecond = waterVaporMolPerSecond.clone();
            aqueousLiquidMolPerSecond = aqueousLiquidMolPerSecond.clone();
            wetMask = wetMask.clone();
            saturationPressurePascal = saturationPressurePascal.clone();
            waterPartialPressurePascal = waterPartialPressurePascal.clone();
            hydrocarbonPartialPressurePascal = hydrocarbonPartialPressurePascal.clone();
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    /** Expected numerical/physical terminal condition; callers must not turn this into a nominal wet result. */
    public record Failure(FailureCode code, String detail, Diagnostics diagnostics) implements Outcome {
        public Failure {
            code = Objects.requireNonNull(code, "code");
            detail = Objects.requireNonNull(detail, "detail");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    public enum FailureCode {
        WATER_PROPERTY_FAILURE,
        INVALID_WET_SATURATION_BRANCH,
        PURE_STEAM_SATURATION_REQUIRES_ENERGY,
        TRIDIAGONAL_BREAKDOWN,
        TRIDIAGONAL_BACKWARD_ERROR,
        NEGATIVE_WATER_PHASE_FLOW,
        WATER_ACTIVE_SET_FAILURE,
        WATER_BALANCE_FAILURE,
        WATER_COMPLEMENTARITY_FAILURE
    }

    /** Bounded evidence suitable for solver diagnostics and no wider than the outer diagnostic event contract. */
    public record Diagnostics(
            int maskPasses,
            List<String> maskTransitions,
            double maximumThomasBackwardError,
            double maximumWaterBalanceResidual,
            double maximumComplementarityResidual) {
        public Diagnostics {
            if (maskPasses < 0 || maskPasses > MAX_WATER_MASK_PASSES || !Double.isFinite(maximumThomasBackwardError)
                    && !Double.isNaN(maximumThomasBackwardError) || !Double.isFinite(maximumWaterBalanceResidual)
                    && !Double.isNaN(maximumWaterBalanceResidual) || !Double.isFinite(maximumComplementarityResidual)
                    && !Double.isNaN(maximumComplementarityResidual)) {
                throw new IllegalArgumentException("Water active-set diagnostics are invalid");
            }
            maskTransitions = List.copyOf(maskTransitions);
            if (maskTransitions.size() > MAX_TRANSITIONS_REPORTED) {
                throw new IllegalArgumentException("Water active-set transition diagnostics exceed their cap");
            }
        }
    }

    /** Immutable caller input. The source arrays are cloned to keep worker snapshots isolated from server state. */
    public record Input(
            double[] temperatureKelvin,
            double[] totalPressurePascal,
            double[] dryHydrocarbonVaporMolPerSecond,
            double[] waterFeedMolPerSecondByNode,
            boolean[] previousWetMask) {
        public Input {
            Objects.requireNonNull(temperatureKelvin, "temperatureKelvin");
            Objects.requireNonNull(totalPressurePascal, "totalPressurePascal");
            Objects.requireNonNull(dryHydrocarbonVaporMolPerSecond, "dryHydrocarbonVaporMolPerSecond");
            Objects.requireNonNull(waterFeedMolPerSecondByNode, "waterFeedMolPerSecondByNode");
            Objects.requireNonNull(previousWetMask, "previousWetMask");
            int nodes = temperatureKelvin.length;
            if (nodes < 4 || totalPressurePascal.length != nodes || dryHydrocarbonVaporMolPerSecond.length != nodes
                    || waterFeedMolPerSecondByNode.length != nodes || previousWetMask.length != nodes) {
                throw new IllegalArgumentException("Water active-set arrays must share a condenser/tray/reboiler node axis");
            }
            temperatureKelvin = temperatureKelvin.clone();
            totalPressurePascal = totalPressurePascal.clone();
            dryHydrocarbonVaporMolPerSecond = dryHydrocarbonVaporMolPerSecond.clone();
            waterFeedMolPerSecondByNode = waterFeedMolPerSecondByNode.clone();
            previousWetMask = previousWetMask.clone();
            for (int node = 0; node < nodes; node++) {
                if (!Double.isFinite(temperatureKelvin[node]) || !Double.isFinite(totalPressurePascal[node])
                        || totalPressurePascal[node] <= 0.0 || !Double.isFinite(dryHydrocarbonVaporMolPerSecond[node])
                        || dryHydrocarbonVaporMolPerSecond[node] < 0.0 || !Double.isFinite(waterFeedMolPerSecondByNode[node])
                        || waterFeedMolPerSecondByNode[node] < 0.0) {
                    throw new IllegalArgumentException("Water active-set input contains an invalid node value");
                }
            }
            if (waterFeedMolPerSecondByNode[0] != 0.0 || waterFeedMolPerSecondByNode[nodes - 1] != 0.0) {
                throw new IllegalArgumentException("Water feeds may connect only to tray nodes");
            }
        }

        public int nodeCount() { return temperatureKelvin.length; }

        private void copyInto(Workspace workspace) {
            System.arraycopy(temperatureKelvin, 0, workspace.temperatureKelvin, 0, temperatureKelvin.length);
            System.arraycopy(totalPressurePascal, 0, workspace.totalPressurePascal, 0, totalPressurePascal.length);
            System.arraycopy(dryHydrocarbonVaporMolPerSecond, 0, workspace.dryHydrocarbonVapor, 0,
                    dryHydrocarbonVaporMolPerSecond.length);
            System.arraycopy(waterFeedMolPerSecondByNode, 0, workspace.waterFeed, 0,
                    waterFeedMolPerSecondByNode.length);
            System.arraycopy(previousWetMask, 0, workspace.wetMask, 0, previousWetMask.length);
        }
    }

    /** Caller-owned arrays; one workspace is intended to live for the lifetime of a column-worker solve. */
    public static final class Workspace {
        private final int nodes;
        private final double[] temperatureKelvin;
        private final double[] totalPressurePascal;
        private final double[] dryHydrocarbonVapor;
        private final double[] waterFeed;
        private final boolean[] wetMask;
        private final double[] saturationPressurePascal;
        private final double[] saturatedSteamRate;
        private final double[] scalar;
        private final double[] waterVapor;
        private final double[] aqueousLiquid;
        private final double[] waterPartialPressurePascal;
        private final double[] hydrocarbonPartialPressurePascal;
        private final double[] lower;
        private final double[] diagonal;
        private final double[] upper;
        private final double[] rightHandSide;
        private final double[] cPrime;
        private final double[] dPrime;
        private final MaskViolation[] violations;
        private double maximumBackwardError;
        private double maximumBalanceResidual;
        private double maximumComplementarityResidual;

        public Workspace(int nodes) {
            if (nodes < 4) throw new IllegalArgumentException("Water active set requires condenser, at least two trays, and reboiler");
            this.nodes = nodes;
            temperatureKelvin = new double[nodes];
            totalPressurePascal = new double[nodes];
            dryHydrocarbonVapor = new double[nodes];
            waterFeed = new double[nodes];
            wetMask = new boolean[nodes];
            saturationPressurePascal = new double[nodes];
            saturatedSteamRate = new double[nodes];
            scalar = new double[nodes];
            waterVapor = new double[nodes];
            aqueousLiquid = new double[nodes];
            waterPartialPressurePascal = new double[nodes];
            hydrocarbonPartialPressurePascal = new double[nodes];
            lower = new double[nodes];
            diagonal = new double[nodes];
            upper = new double[nodes];
            rightHandSide = new double[nodes];
            cPrime = new double[nodes];
            dPrime = new double[nodes];
            violations = new MaskViolation[nodes];
        }
    }

    private enum MaskViolation {
        NONE,
        WET_NEGATIVE_AQUEOUS,
        DRY_SUPERSATURATED,
        PURE_STEAM_SUPERSATURATED
    }
}

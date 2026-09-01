package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.Objects;

/**
 * Wide-volatility sequential preconditioner with component TDMA material solves and a local tridiagonal
 * energy-temperature correction.
 *
 * <p>This produces only a finite seed for the simultaneous MESH corrector; it is not a publication path.</p>
 */
final class V3SumRatesPreconditioner implements V3SequentialPreconditioner {
    static final V3SumRatesPreconditioner INSTANCE = new V3SumRatesPreconditioner();

    private static final int MAXIMUM_SWEEPS = 12;
    private static final double ENERGY_TEMPERATURE_DIFFERENCE_KELVIN = 1.0;
    private static final double MAXIMUM_TEMPERATURE_CHANGE_KELVIN = 5.0;
    private static final double TEMPERATURE_DAMPING = 0.5;

    private V3SumRatesPreconditioner() {}

    @Override
    public V3SequentialPreconditioner.Id id() {
        return V3SequentialPreconditioner.Id.SUM_RATES;
    }

    @Override
    public V3SequentialPreconditioner.Result prepare(
            V3SequentialPreconditioner.Request request, V3ThermoModel thermo, V3ThermoWorkspace workspace) {
        request = Objects.requireNonNull(request, "request");
        thermo = Objects.requireNonNull(thermo, "thermo");
        workspace = Objects.requireNonNull(workspace, "workspace");
        request.control().checkpoint();
        if (request.problem().hasSideDraws()) {
            return new V3SequentialPreconditioner.Result.NotApplicable(V3SequentialPreconditioner.Failure.INVALID_STATE,
                    new V3SequentialPreconditioner.Evidence(id(), 0, "side draws"));
        }
        try {
            V3FlashResult feedFlash = thermo.flashTP(request.problem().input().feedTemperatureKelvin(),
                    request.problem().nodePressurePascal(request.problem().topology().feedTrayNumber()),
                    request.problem().input().feedComponentMolarFlowsMolPerSecond(), workspace);
            return prepare(request, thermo, workspace, feedFlash.molarEnthalpyJoulesPerMol());
        } catch (V3ThermoException failure) {
            return failed(V3SequentialPreconditioner.Failure.PROPERTY_DOMAIN, 0, failure.getMessage());
        } catch (IllegalArgumentException failure) {
            return failed(V3SequentialPreconditioner.Failure.INVALID_STATE, 0, failure.getMessage());
        }
    }

    private V3SequentialPreconditioner.Result prepare(
            V3SequentialPreconditioner.Request request,
            V3ThermoModel thermo,
            V3ThermoWorkspace workspace,
            double feedMolarEnthalpyJoulesPerMol) {
        V3ColumnProblem problem = request.problem();
        V3ColumnTopology topology = problem.topology();
        double[][] liquid = V3ColumnInitializer.flows(request.seed(), true);
        double[][] vapor = V3ColumnInitializer.flows(request.seed(), false);
        double[] temperatures = V3ColumnInitializer.temperatures(request.seed());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, feedMolarEnthalpyJoulesPerMol);
        double initialEnergyNorm = Double.NaN;
        double finalEnergyNorm = Double.NaN;
        int completedSweeps = 0;
        for (int sweep = 1; sweep <= MAXIMUM_SWEEPS; sweep++) {
            request.control().checkpoint();
            double[][] phaseRatios = V3ColumnInitializer.phaseRatios(problem, thermo, workspace, liquid, vapor, temperatures);
            V3ColumnInitializer.solveMaterialBalances(problem, phaseRatios, liquid, vapor);
            V3DryMeshState state = new V3DryMeshState(topology, request.seed().componentCount(), liquid, vapor, temperatures);
            V3MeshResidual residual = evaluator.evaluate(state, workspace);
            double[] energyResidual = energyResiduals(residual, topology);
            double energyNorm = maximumAbsoluteScaledEnergy(residual);
            if (sweep == 1) initialEnergyNorm = energyNorm;
            double[] temperatureCorrection = energyTemperatureCorrection(
                    evaluator, state, workspace, energyResidual, topology, request.control());
            double maximumChange = applyTemperatureCorrection(temperatures, temperatureCorrection, topology);
            finalEnergyNorm = energyNorm;
            completedSweeps = sweep;
            if (maximumChange <= 1.0e-4) break;
        }
        V3DryMeshState candidate = new V3DryMeshState(topology, request.seed().componentCount(), liquid, vapor, temperatures);
        V3MeshResidual finalResidual = evaluator.evaluate(candidate, workspace);
        finalEnergyNorm = maximumAbsoluteScaledEnergy(finalResidual);
        if (!Double.isFinite(initialEnergyNorm) || !Double.isFinite(finalEnergyNorm)
                || finalEnergyNorm > initialEnergyNorm * (1.0 + 1.0e-8)) {
            return failed(V3SequentialPreconditioner.Failure.INVALID_STATE, completedSweeps,
                    "sum-rates energy correction did not improve its seed residual");
        }
        return new V3SequentialPreconditioner.Result.Prepared(candidate,
                new V3SequentialPreconditioner.Evidence(id(), completedSweeps,
                "component TDMA plus tridiagonal energy correction reduced scaled energy from "
                        + initialEnergyNorm + " to " + finalEnergyNorm));
    }

    private static double[] energyResiduals(V3MeshResidual residual, V3ColumnTopology topology) {
        double[] values = new double[topology.reboilerNode()];
        boolean[] present = new boolean[values.length];
        for (V3MeshResidual.Row row : residual.rows()) {
            if (row.equation().family() != V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE) continue;
            int node = row.equation().node();
            if (node < 1 || node > topology.reboilerNode()) {
                throw new IllegalArgumentException("V3 sum-rates energy row is outside its non-condenser nodes");
            }
            values[node - 1] = row.physicalValue();
            present[node - 1] = true;
        }
        for (boolean energyRow : present) {
            if (!energyRow) throw new IllegalArgumentException("V3 sum-rates energy correction is missing a row");
        }
        return values;
    }

    private static double maximumAbsoluteScaledEnergy(V3MeshResidual residual) {
        double maximum = 0.0;
        boolean found = false;
        for (V3MeshResidual.Row row : residual.rows()) {
            if (row.equation().family() != V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE) continue;
            maximum = Math.max(maximum, Math.abs(row.scaledValue()));
            found = true;
        }
        if (!found) throw new IllegalArgumentException("V3 sum-rates residual is missing energy rows");
        return maximum;
    }

    private static double[] energyTemperatureCorrection(
            V3MeshResidualEvaluator evaluator,
            V3DryMeshState state,
            V3ThermoWorkspace workspace,
            double[] energyResidual,
            V3ColumnTopology topology,
            V3SolveControl control) {
        int size = energyResidual.length;
        double[] lower = new double[size];
        double[] diagonal = new double[size];
        double[] upper = new double[size];
        for (int column = 0; column < size; column++) {
            control.checkpoint();
            V3DryMeshState perturbed = perturbTemperature(
                    state, topology, column + 1, ENERGY_TEMPERATURE_DIFFERENCE_KELVIN);
            double[] perturbedEnergy = energyResiduals(evaluator.evaluate(perturbed, workspace), topology);
            for (int row = Math.max(0, column - 1); row <= Math.min(size - 1, column + 1); row++) {
                double derivative = (perturbedEnergy[row] - energyResidual[row]) / ENERGY_TEMPERATURE_DIFFERENCE_KELVIN;
                if (!Double.isFinite(derivative)) {
                    throw new IllegalArgumentException("V3 sum-rates energy temperature derivative is not finite");
                }
                if (row == column) diagonal[row] = derivative;
                else if (row == column - 1) upper[row] = derivative;
                else lower[row] = derivative;
            }
        }
        double[] rightHandSide = new double[size];
        for (int index = 0; index < size; index++) rightHandSide[index] = -energyResidual[index];
        return solveTridiagonalCorrection(lower, diagonal, upper, rightHandSide);
    }

    private static V3DryMeshState perturbTemperature(
            V3DryMeshState state, V3ColumnTopology topology, int node, double differenceKelvin) {
        double[][] liquid = V3ColumnInitializer.flows(state, true);
        double[][] vapor = V3ColumnInitializer.flows(state, false);
        double[] temperatures = V3ColumnInitializer.temperatures(state);
        temperatures[node] += differenceKelvin;
        if (!(temperatures[node] > 0.0) || !Double.isFinite(temperatures[node])) {
            throw new IllegalArgumentException("V3 sum-rates temperature perturbation leaves the physical domain");
        }
        return new V3DryMeshState(topology, state.componentCount(), liquid, vapor, temperatures);
    }

    private static double[] solveTridiagonalCorrection(
            double[] lower, double[] diagonal, double[] upper, double[] rightHandSide) {
        int size = diagonal.length;
        if (size == 0 || lower.length != size || upper.length != size || rightHandSide.length != size) {
            throw new IllegalArgumentException("V3 sum-rates temperature system dimensions disagree");
        }
        double[] transformedUpper = new double[size];
        double[] transformedRightHandSide = new double[size];
        double pivot = diagonal[0];
        requireFinitePivot(pivot);
        transformedUpper[0] = upper[0] / pivot;
        transformedRightHandSide[0] = rightHandSide[0] / pivot;
        for (int row = 1; row < size; row++) {
            pivot = diagonal[row] - lower[row] * transformedUpper[row - 1];
            requireFinitePivot(pivot);
            transformedUpper[row] = row == size - 1 ? 0.0 : upper[row] / pivot;
            transformedRightHandSide[row] = (rightHandSide[row]
                    - lower[row] * transformedRightHandSide[row - 1]) / pivot;
        }
        double[] correction = new double[size];
        correction[size - 1] = transformedRightHandSide[size - 1];
        for (int row = size - 2; row >= 0; row--) {
            correction[row] = transformedRightHandSide[row] - transformedUpper[row] * correction[row + 1];
        }
        for (double value : correction) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("V3 sum-rates temperature correction is invalid");
        }
        return correction;
    }

    private static double applyTemperatureCorrection(
            double[] temperatures, double[] correction, V3ColumnTopology topology) {
        double maximumChange = 0.0;
        for (int index = 0; index < correction.length; index++) {
            int node = index + 1;
            if (node > topology.reboilerNode()) throw new IllegalArgumentException("V3 sum-rates correction node is invalid");
            double change = TEMPERATURE_DAMPING * Math.clamp(correction[index],
                    -MAXIMUM_TEMPERATURE_CHANGE_KELVIN, MAXIMUM_TEMPERATURE_CHANGE_KELVIN);
            double updated = temperatures[node] + change;
            if (!(updated > 0.0) || !Double.isFinite(updated)) {
                throw new IllegalArgumentException("V3 sum-rates correction leaves the temperature domain");
            }
            temperatures[node] = updated;
            maximumChange = Math.max(maximumChange, Math.abs(change));
        }
        return maximumChange;
    }

    private static void requireFinitePivot(double pivot) {
        if (!Double.isFinite(pivot) || Math.abs(pivot) <= 1.0e-12) {
            throw new IllegalArgumentException("V3 sum-rates energy temperature system is singular");
        }
    }

    private V3SequentialPreconditioner.Result.Failed failed(
            V3SequentialPreconditioner.Failure reason, int sweeps, String detail) {
        String bounded = detail == null || detail.isBlank() ? "sum-rates preconditioner failed" : detail;
        if (bounded.length() > 256) bounded = bounded.substring(0, 256);
        return new V3SequentialPreconditioner.Result.Failed(
                reason, new V3SequentialPreconditioner.Evidence(id(), sweeps, bounded));
    }
}

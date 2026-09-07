package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Enthalpy-consistent temperature shift for a continuation rung whose stage heat or steam has just changed.
 *
 * <p>Adding a cooler or removing a surrogate boilup moves the whole column's temperature profile, not its
 * flows: the measured signature of a stalled heat or steam rung is every tray energy row short by the same
 * {@code dQ/N} while the material and equilibrium rows are already closed. A common temperature shift changes
 * every energy row by the same amount and barely moves the others, so the merit has a long flat valley there
 * and the line search crawls along it. This class takes the one step down that valley before Newton starts.</p>
 *
 * <p>With every flow frozen at the seed, the tray energy rows are linearised in the tray temperatures alone:</p>
 *
 * <pre>
 *   dE_n/dT_n     = -(L_n Cp_L,n + V_n Cp_V,n)
 *   dE_n/dT_(n-1) = (1 - w_(n-1)) L_(n-1) Cp_L,(n-1)
 *   dE_n/dT_(n+1) = V_(n+1) Cp_V,(n+1)
 * </pre>
 *
 * <p>{@code L Cp_L} and {@code V Cp_V} are the phase enthalpy-rate slopes in W/K, taken as a central one-kelvin
 * finite difference of the evaluator's own phase enthalpy at the node's frozen composition, so the water term a
 * wet node carries is differentiated with the hydrocarbon term. {@code w} is the side-draw withdrawal fraction.
 * The condenser node has no temperature unknown (its outlet temperature is a specification) and therefore no
 * row and no column; the sump node has both. The resulting tridiagonal system {@code J_T dT = -E(seed)} is
 * solved by the Thomas algorithm and the whole shift is then scaled so that no tray moves by more than
 * {@value #MAXIMUM_SHIFT_KELVIN} K.</p>
 *
 * <p>The system is close to singular in the common mode, and that is the physics rather than a defect: moving
 * every tray by the same amount changes what a tray receives as much as what it sends, so the interior rows
 * barely notice and only the two boundaries — a condenser held at its specified outlet, and the sump — absorb
 * the change. That near-null direction is the valley, and taking a bounded step along it is the point.</p>
 *
 * <p>This produces a seed only. Nothing here can publish a result: the shifted state still has to pass the
 * whole simultaneous MESH solve and the fresh acceptance audit exactly as an unshifted seed does.</p>
 */
final class V3EnergyShiftPredictor {
    /** Per-tray bound on one prediction; a rung that wants more than this gets a scaled-down whole step. */
    static final double MAXIMUM_SHIFT_KELVIN = 40.0;

    private static final double DIFFERENCE_KELVIN = 1.0;

    private V3EnergyShiftPredictor() {}

    /**
     * One prediction outcome.
     *
     * <p>{@code shiftKelvin} holds the clamped temperature change of nodes 1..reboiler in ledger order and is
     * empty when nothing was predicted. It is returned as a shift rather than as a state so that the caller can
     * apply it to whichever seed it is about to hand to the attempt: the flows never move, so the same vector
     * is valid for the truncation-projected state the prediction was measured on and for the untruncated state
     * the attempt will prepare for itself.</p>
     *
     * <p>The two energy norms are the largest absolute scaled energy residual of the measured state before and
     * after the shift, and are telemetry only — the prediction is not gated on them, because the shift is a
     * step on the energy rows and the equilibrium rows are expected to absorb part of it.</p>
     */
    record Prediction(
            double[] shiftKelvin,
            boolean applied,
            double largestShiftKelvin,
            double scaledEnergyBefore,
            double scaledEnergyAfter,
            String note) {
        Prediction {
            shiftKelvin = Objects.requireNonNull(shiftKelvin, "shiftKelvin").clone();
            note = Objects.requireNonNull(note, "note");
        }

        @Override public double[] shiftKelvin() { return shiftKelvin.clone(); }

        /** Applies this prediction to a state of the same topology; returns it unchanged when nothing applies. */
        V3DryMeshState applyTo(V3DryMeshState state, V3ColumnTopology topology) {
            return applied ? shifted(state, topology, shiftKelvin) : state;
        }
    }

    /** Carries the reason no shift was taken. */
    private static Prediction unavailable(String note) {
        return new Prediction(new double[0], false, 0.0, Double.NaN, Double.NaN, note);
    }

    /**
     * Predicts the temperature shift that closes the energy rows of {@code problem} at {@code seed}, whose
     * profile still belongs to the previous rung's duties.
     *
     * <p>{@code problem} and {@code seed} must be the pair the attempt will actually solve, truncation support
     * included: a state whose truncated points hold exact zeros cannot be evaluated against an identity-support
     * problem.</p>
     *
     * <p>Every failure mode returns an empty prediction: a singular system, a property call outside its domain
     * and a node the state cannot describe are all reasons to let Newton start where it would have started.
     * Cancellation is the one exception and propagates.</p>
     */
    static Prediction predict(
            V3ColumnProblem problem,
            V3ThermoModel thermo,
            double feedMolarEnthalpyJoulesPerMol,
            V3DryMeshState seed,
            V3ThermoWorkspace workspace,
            V3SolveControl control) {
        problem = Objects.requireNonNull(problem, "problem");
        thermo = Objects.requireNonNull(thermo, "thermo");
        seed = Objects.requireNonNull(seed, "seed");
        workspace = Objects.requireNonNull(workspace, "workspace");
        control = Objects.requireNonNull(control, "control");
        V3ColumnTopology topology = problem.topology();
        int size = topology.reboilerNode();
        if (size < 1 || seed.nodeCount() != topology.nodeCount()) {
            return unavailable("state does not match the rung problem");
        }
        try {
            control.checkpoint();
            V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                    problem, thermo, feedMolarEnthalpyJoulesPerMol);
            V3MeshResidual residual = evaluator.evaluate(seed, workspace);
            double[] energy = energyResiduals(residual, topology);
            double before = largestScaledEnergy(residual);

            double[] liquidSlope = new double[size];
            double[] vaporSlope = new double[size];
            double[] retainedLiquid = new double[size];
            for (int node = 1; node <= size; node++) {
                control.checkpoint();
                V3MeshResidualEvaluator.LocalNodeTerms warmer =
                        evaluator.localTerms(shifted(seed, topology, node, DIFFERENCE_KELVIN), node, workspace);
                V3MeshResidualEvaluator.LocalNodeTerms colder =
                        evaluator.localTerms(shifted(seed, topology, node, -DIFFERENCE_KELVIN), node, workspace);
                liquidSlope[node - 1] =
                        (warmer.liquidPhaseEnergy() - colder.liquidPhaseEnergy()) / (2.0 * DIFFERENCE_KELVIN);
                vaporSlope[node - 1] =
                        (warmer.vaporPhaseEnergy() - colder.vaporPhaseEnergy()) / (2.0 * DIFFERENCE_KELVIN);
                retainedLiquid[node - 1] = node <= topology.trayCount()
                        ? 1.0 - problem.liquidWithdrawalFraction(seed, node) : 0.0;
                if (!Double.isFinite(liquidSlope[node - 1]) || !Double.isFinite(vaporSlope[node - 1])) {
                    return unavailable("phase enthalpy slope of node " + node + " is not finite");
                }
            }

            double[] shift = clamped(solveTridiagonal(
                    assemble(liquidSlope, vaporSlope, retainedLiquid, energy)), MAXIMUM_SHIFT_KELVIN);
            double largest = 0.0;
            for (double value : shift) largest = Math.max(largest, Math.abs(value));
            if (!(largest > 0.0)) return unavailable("predicted shift is zero");

            double after = largestScaledEnergy(evaluator.evaluate(shifted(seed, topology, shift), workspace));
            return new Prediction(shift, true, largest, before, after,
                    "energy shift applied, largest " + largest + " K");
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (V3ThermoException | IllegalArgumentException | IllegalStateException failure) {
            String detail = failure.getMessage();
            return unavailable(detail == null || detail.isBlank() ? "energy shift unavailable" : detail);
        }
    }

    /**
     * The valley signature: strictly more than half of the energy rows are off with the same sign.
     *
     * <p>A rung that has landed in the flat energy valley is short (or long) on every tray at once; a rung
     * whose energy rows already alternate in sign is a local mismatch that Newton resolves directly.</p>
     */
    static boolean hasCommonModeSignature(double[] energyResidual) {
        int positive = 0;
        int negative = 0;
        for (double value : energyResidual) {
            if (value > 0.0) positive++;
            else if (value < 0.0) negative++;
        }
        return 2 * Math.max(positive, negative) > energyResidual.length;
    }

    /** The frozen-flow energy/temperature system of one state, in ledger node order 1..reboiler. */
    record TemperatureSystem(double[] lower, double[] diagonal, double[] upper, double[] rightHandSide) {
        TemperatureSystem {
            lower = Objects.requireNonNull(lower, "lower").clone();
            diagonal = Objects.requireNonNull(diagonal, "diagonal").clone();
            upper = Objects.requireNonNull(upper, "upper").clone();
            rightHandSide = Objects.requireNonNull(rightHandSide, "rightHandSide").clone();
            if (diagonal.length == 0 || lower.length != diagonal.length || upper.length != diagonal.length
                    || rightHandSide.length != diagonal.length) {
                throw new IllegalArgumentException("V3 energy-shift system dimensions disagree");
            }
        }

        int size() {
            return diagonal.length;
        }
    }

    /**
     * Assembles {@code J_T dT = -E} from the per-node phase enthalpy slopes and the retained liquid fractions.
     *
     * <p>All four arrays are indexed by {@code node - 1} over nodes 1..reboiler. {@code retainedLiquid[i]} is
     * {@code 1 - w} of the liquid leaving node {@code i + 1} towards {@code i + 2}; its last entry is unused
     * because the sump has nothing below it.</p>
     */
    static TemperatureSystem assemble(
            double[] liquidEnergySlope, double[] vaporEnergySlope, double[] retainedLiquid, double[] energyResidual) {
        int size = energyResidual.length;
        if (size == 0 || liquidEnergySlope.length != size || vaporEnergySlope.length != size
                || retainedLiquid.length != size) {
            throw new IllegalArgumentException("V3 energy-shift slope dimensions disagree");
        }
        double[] lower = new double[size];
        double[] diagonal = new double[size];
        double[] upper = new double[size];
        double[] rightHandSide = new double[size];
        for (int row = 0; row < size; row++) {
            diagonal[row] = -(liquidEnergySlope[row] + vaporEnergySlope[row]);
            // The condenser temperature is a specification, so the first row has no column to its left.
            lower[row] = row == 0 ? 0.0 : retainedLiquid[row - 1] * liquidEnergySlope[row - 1];
            upper[row] = row == size - 1 ? 0.0 : vaporEnergySlope[row + 1];
            rightHandSide[row] = -energyResidual[row];
        }
        return new TemperatureSystem(lower, diagonal, upper, rightHandSide);
    }

    /** Thomas algorithm; throws when a pivot collapses so the caller can decline the prediction. */
    static double[] solveTridiagonal(TemperatureSystem system) {
        int size = system.size();
        double[] lower = system.lower();
        double[] diagonal = system.diagonal();
        double[] upper = system.upper();
        double[] rightHandSide = system.rightHandSide();
        double[] sweptUpper = new double[size];
        double[] sweptRightHandSide = new double[size];
        double pivot = diagonal[0];
        requirePivot(pivot);
        sweptUpper[0] = upper[0] / pivot;
        sweptRightHandSide[0] = rightHandSide[0] / pivot;
        for (int row = 1; row < size; row++) {
            pivot = diagonal[row] - lower[row] * sweptUpper[row - 1];
            requirePivot(pivot);
            sweptUpper[row] = row == size - 1 ? 0.0 : upper[row] / pivot;
            sweptRightHandSide[row] = (rightHandSide[row] - lower[row] * sweptRightHandSide[row - 1]) / pivot;
        }
        double[] solution = new double[size];
        solution[size - 1] = sweptRightHandSide[size - 1];
        for (int row = size - 2; row >= 0; row--) {
            solution[row] = sweptRightHandSide[row] - sweptUpper[row] * solution[row + 1];
        }
        for (double value : solution) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("V3 energy-shift solution is not finite");
        }
        return solution;
    }

    /**
     * Bounds the largest tray move to {@code limitKelvin} by scaling the whole vector, not by truncating each
     * entry.
     *
     * <p>Scaling keeps the direction the linear system chose, so the linear model's residual falls from
     * {@code E} to {@code (1 - t) E} for the applied fraction {@code t} and the prediction can never make the
     * energy rows worse in its own model. Truncating each entry does not have that property, and measurably
     * loses it: on the source three-cooler column the per-entry form saturated most trays at the bound, turned
     * the direction into a nearly uniform shift and raised the first heat rung's scaled energy residual from
     * 2.06e-2 to 8.16e-2, where scaling lowers it instead.</p>
     */
    static double[] clamped(double[] shift, double limitKelvin) {
        double largest = 0.0;
        for (double value : shift) largest = Math.max(largest, Math.abs(value));
        double fraction = largest > limitKelvin ? limitKelvin / largest : 1.0;
        double[] bounded = new double[shift.length];
        for (int index = 0; index < shift.length; index++) bounded[index] = fraction * shift[index];
        return bounded;
    }

    /** Physical energy residuals of nodes 1..reboiler, in ledger node order. */
    static double[] energyResiduals(V3MeshResidual residual, V3ColumnTopology topology) {
        double[] values = new double[topology.reboilerNode()];
        boolean[] present = new boolean[values.length];
        for (V3MeshResidual.Row row : residual.rows()) {
            if (row.equation().family() != V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE) continue;
            int node = row.equation().node();
            if (node < 1 || node > topology.reboilerNode()) {
                throw new IllegalArgumentException("V3 energy-shift row is outside its non-condenser nodes");
            }
            values[node - 1] = row.physicalValue();
            present[node - 1] = true;
        }
        for (boolean energyRow : present) {
            if (!energyRow) throw new IllegalArgumentException("V3 energy-shift residual is missing a row");
        }
        return values;
    }

    private static double largestScaledEnergy(V3MeshResidual residual) {
        double maximum = 0.0;
        for (V3MeshResidual.Row row : residual.rows()) {
            if (row.equation().family() != V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE) continue;
            maximum = Math.max(maximum, Math.abs(row.scaledValue()));
        }
        return maximum;
    }

    private static void requirePivot(double pivot) {
        if (!Double.isFinite(pivot) || Math.abs(pivot) <= 1.0e-12) {
            throw new IllegalArgumentException("V3 energy-shift temperature system is singular");
        }
    }

    private static V3DryMeshState shifted(
            V3DryMeshState state, V3ColumnTopology topology, int node, double differenceKelvin) {
        double[] shift = new double[topology.reboilerNode()];
        shift[node - 1] = differenceKelvin;
        return shifted(state, topology, shift);
    }

    /** Applies a whole shift vector over nodes 1..reboiler; the condenser node keeps its specified outlet. */
    private static V3DryMeshState shifted(V3DryMeshState state, V3ColumnTopology topology, double[] shift) {
        double[][] liquid = V3ColumnInitializer.flows(state, true);
        double[][] vapor = V3ColumnInitializer.flows(state, false);
        double[] temperatures = V3ColumnInitializer.temperatures(state);
        for (int node = 1; node <= topology.reboilerNode(); node++) {
            double updated = temperatures[node] + shift[node - 1];
            if (!Double.isFinite(updated) || updated <= 0.0) {
                throw new IllegalArgumentException("V3 energy shift leaves the physical temperature domain");
            }
            temperatures[node] = updated;
        }
        return new V3DryMeshState(topology, state.componentCount(), liquid, vapor, temperatures);
    }
}

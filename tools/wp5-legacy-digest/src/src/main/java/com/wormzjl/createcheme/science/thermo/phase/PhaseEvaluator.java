package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.thermo.PhaseRoot;
import java.util.List;
import java.util.Set;

/**
 * One family of fluid phase models over a component basis: the P2 phase-evaluator contract of the unified multiphase
 * thermodynamics plan (batch 2026-09-24-coolprop-low-temperature, section 4).
 *
 * <p>An evaluator turns (T, P, amounts, root preference) into a {@link PhaseState} at that pressure: every phase is
 * evaluated at the state pressure, never through a reference pressure. The ideal-gas parts come from the
 * {@link com.wormzjl.createcheme.science.thermo.IdealGasFunction}s it was built with (one per component, the data
 * spine), the residual parts from the family's own model, so two families over the same spine share every enthalpy
 * and entropy origin and differ only in their residual parts.</p>
 *
 * <p>{@link #family()} is part of the thermodynamic identity ({@link ThermoIdentity}): a change of the residual
 * formulation is a new family id. Derivatives are optional per family and declared by {@link #capabilities()};
 * {@link PhaseDerivatives} refuses to hand out a block its family did not provide.</p>
 *
 * <p>Errors: malformed input (wrong length, negative or non-finite amounts, no material, non-positive T or P) is an
 * {@link IllegalArgumentException}; a state outside the declared range of a carried component's data is a
 * {@link com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation}; a state the model itself cannot
 * evaluate (no physical root, a mechanically unstable root, a derivative at root coalescence) is an
 * {@link IllegalArgumentException} or {@link IllegalStateException} from the model.</p>
 */
public interface PhaseEvaluator {
    /** Derivative blocks a family may provide; see {@link PhaseDerivatives}. */
    enum Capability {
        /** {@code d ln phi_i / d n_j}. */
        FUGACITY_COMPOSITION_DERIVATIVES,
        /** {@code d ln phi_i / dT} and {@code d ln phi_i / dP}. */
        FUGACITY_STATE_DERIVATIVES,
        /** {@code dv/dT} and {@code dv/dP}. */
        VOLUMETRIC_DERIVATIVES,
        /** {@code dh/dT} ({@code c_p}) and {@code dh/dP}. */
        CALORIC_DERIVATIVES
    }

    /** Opaque, caller-owned scratch of one evaluator for one thread. */
    interface Workspace {}

    /** Evaluator family id, e.g. {@code pr78_translated_v1}. */
    String family();

    /** The component basis, in the order every amount array uses. */
    List<String> components();

    default int componentCount() { return components().size(); }

    /** The derivative blocks {@link #derivatives} provides; empty for a family without derivatives. */
    Set<Capability> capabilities();

    Workspace newWorkspace();

    default PhaseState newState() { return new PhaseState(componentCount()); }

    default PhaseDerivatives newDerivatives() { return new PhaseDerivatives(componentCount()); }

    /**
     * Evaluates one phase of {@code amounts} at the state's own {@code temperature} and {@code pressure} on the
     * preferred root, which is the one taken where the model has more than one; {@link PhaseState#kind()} states the
     * root situation actually met. Fills and returns {@code out}.
     */
    PhaseState evaluate(double temperature, double pressure, double[] amounts, PhaseRoot preference,
                        Workspace workspace, PhaseState out);

    /**
     * Fills {@code out} with the phase and every derivative block of {@link #capabilities()}, on the same root as
     * {@link #evaluate} would take. A family without derivatives throws {@link UnsupportedOperationException}.
     */
    PhaseDerivatives derivatives(double temperature, double pressure, double[] amounts, PhaseRoot preference,
                                 Workspace workspace, PhaseDerivatives out);
}

package com.wormzjl.createcheme.science.column.nextgen;

/**
 * Terminal states emitted by the dry inside-out solver.
 *
 * <p>The solver deliberately returns one of these values instead of publishing a partially
 * converged profile.  Water-specific work is intentionally deferred to the Phase-5 solver path;
 * a utility-water input is therefore a typed unsupported dry-model outcome, not an implicit
 * approximation.</p>
 */
public enum DrySolverFailureCode {
    INVALID_INPUT,
    PROPERTY_PACKAGE_MISMATCH,
    DOF_MISMATCH,
    INFEASIBLE_SPECIFICATION,
    PROPERTY_OUT_OF_RANGE,
    EOS_ROOT_FAILURE,
    INITIALIZATION_FAILURE,
    TRIDIAGONAL_BREAKDOWN,
    NEGATIVE_PHASE_FLOW,
    INNER_NONCONVERGENCE,
    OUTER_NONCONVERGENCE,
    COMPONENT_BALANCE_FAILURE,
    TOTAL_HYDROCARBON_BALANCE_FAILURE,
    WATER_BALANCE_FAILURE,
    WATER_ACTIVE_SET_FAILURE,
    WATER_PROPERTY_FAILURE,
    PHASE_REGIME_MISMATCH,
    CONTINUATION_FAILURE,
    ENERGY_BALANCE_FAILURE,
    EQUILIBRIUM_FAILURE,
    RAW_VLE_FAILURE,
    SUM_RATES_CLOSURE_FAILURE,
    COMPOSITION_SUM_FAILURE,
    SPECIFICATION_FAILURE,
    PHASE_VALIDITY_FAILURE,
    STATE_CHANGE_FAILURE,
    CANCELLED,
    DEADLINE_EXCEEDED,
    INTERNAL_INVARIANT_FAILURE
}

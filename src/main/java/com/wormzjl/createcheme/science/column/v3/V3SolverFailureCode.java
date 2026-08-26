package com.wormzjl.createcheme.science.column.v3;

/** Stable terminal non-success categories for the V3 calculator. */
public enum V3SolverFailureCode {
    INVALID_INPUT,
    DOF_MISMATCH,
    INFEASIBLE_SPECIFICATION,
    PROPERTY_OUT_OF_RANGE,
    PHASE_REGIME_MISMATCH,
    INITIALIZATION_FAILURE,
    LINEAR_SOLVE_FAILURE,
    NONCONVERGENCE,
    ACCEPTANCE_AUDIT_FAILURE,
    CANCELLED,
    DEADLINE_EXCEEDED,
    INTERNAL_ERROR
}

package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.Objects;

/** Request-local sequential seed strategy; a prepared state still requires rigorous MESH correction and audit. */
interface V3SequentialPreconditioner {
    Id id();

    Result prepare(Request request, V3ThermoModel thermo, V3ThermoWorkspace workspace);

    /** Stable internal identities for sequential preconditioner strategies. */
    enum Id {
        BUBBLE_POINT,
        SUM_RATES
    }

    /** Immutable input to one request-local sequential preconditioner attempt. */
    record Request(V3ColumnProblem problem, V3DryMeshState seed, V3SolveControl control) {
        public Request {
            problem = Objects.requireNonNull(problem, "problem");
            seed = Objects.requireNonNull(seed, "seed");
            control = Objects.requireNonNull(control, "control");
            if (seed.nodeCount() != problem.topology().nodeCount()
                    || seed.componentCount() != problem.activeComponentBasis().componentCount()) {
                throw new IllegalArgumentException("V3 preconditioner seed does not match its resolved problem");
            }
        }
    }

    /** Typed outcome from a sequential preconditioner; normal inapplicability is not an exception. */
    sealed interface Result permits Result.Prepared, Result.NotApplicable, Result.Failed {
        Evidence evidence();

        /** A finite candidate state for a later rigorous MESH correction. */
        record Prepared(V3DryMeshState state, Evidence evidence) implements Result {
            public Prepared {
                state = Objects.requireNonNull(state, "state");
                evidence = Objects.requireNonNull(evidence, "evidence");
            }
        }

        /** The strategy is not valid for this physical/topological state and must not invent a seed. */
        record NotApplicable(Failure reason, Evidence evidence) implements Result {
            public NotApplicable {
                reason = Objects.requireNonNull(reason, "reason");
                evidence = Objects.requireNonNull(evidence, "evidence");
            }
        }

        /** The strategy was applicable but did not create a finite usable candidate. */
        record Failed(Failure reason, Evidence evidence) implements Result {
            public Failed {
                reason = Objects.requireNonNull(reason, "reason");
                evidence = Objects.requireNonNull(evidence, "evidence");
            }
        }
    }

    /** Bounded immutable evidence from one sequential seed attempt. */
    record Evidence(Id id, int sweeps, String detail) {
        public Evidence {
            id = Objects.requireNonNull(id, "id");
            if (sweeps < 0) throw new IllegalArgumentException("V3 preconditioner sweeps cannot be negative");
            detail = Objects.requireNonNull(detail, "detail");
            if (detail.isBlank() || detail.length() > 256) {
                throw new IllegalArgumentException("V3 preconditioner evidence detail is invalid");
            }
        }
    }

    /** Stable internal non-success causes for sequential preconditioner attempts. */
    enum Failure {
        PROPERTY_DOMAIN,
        INVALID_STATE
    }
}

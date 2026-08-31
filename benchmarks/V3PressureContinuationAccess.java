package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Benchmark-only bridge to the current private calculator paths; no production code or numerical policy is changed.
 *
 * <p>A session retains the original requested input for preferred-branch selection, even when a shorter pressure
 * prefix is requested. Captured prepared seeds and problems can be replayed through the package-local solver with
 * a diagnostic observer. This bridge supplies no timing claims and does not implement the outer alternate-branch
 * or truncation retry dispatcher.</p>
 */
public final class V3PressureContinuationAccess {
    private static final Class<?> POLICY_CLASS = nestedClass("TruncationPolicy");
    private static final Class<?> JACOBIAN_POLICY_CLASS = nestedClass("ContinuationJacobianPolicy");
    private static final Class<?> PASS_CLASS = nestedClass("V3SolvePass");
    private static final Class<?> PREPARED_CLASS = nestedClass("PreparedAttempt");
    private static final Class<?> CORRECTION_CLASS = nestedClass("PhaseCorrection");
    private static final Object OFF = staticField(POLICY_CLASS, "OFF");
    private static final Object PRESSURE_PREDICTOR = enumConstant(JACOBIAN_POLICY_CLASS, "PRESSURE_LOCAL_PREDICTOR");

    private static final Method PREFERRED = method(V3ColumnCalculator.class, "preferredCondenserBranch",
            V3ColumnInput.class, V3SolveControl.class);
    private static final Method WITH_PRESSURE = method(V3ColumnCalculator.class, "withTopPressure",
            V3ColumnInput.class, double.class);
    private static final Method PRESSURE_PATH = method(V3ColumnCalculator.class, "dwsimPressurePath", double.class);
    private static final Method CONTINUATION = method(V3ColumnCalculator.class, "solveDwsimPressureContinuation",
            V3ColumnInput.class, V3PengRobinsonThermo.class, V3SolveControl.class, V3CondenserPhaseBranch.class,
            POLICY_CLASS, V3ColumnCalculator.CondenserAttempts.class);
    private static final Method PREDICT = method(V3ColumnCalculator.class, "solveSingleProblem",
            V3ColumnProblem.class, V3PengRobinsonThermo.class, V3DryMeshState.class, V3SolveControl.class,
            String.class, JACOBIAN_POLICY_CLASS, int.class, POLICY_CLASS);
    private static final Method RECOVER = method(V3ColumnCalculator.class, "recoverWithBubblePointProjection",
            V3ColumnProblem.class, V3PengRobinsonThermo.class, V3DryMeshState.class, V3SolveControl.class,
            String.class, JACOBIAN_POLICY_CLASS, int.class, POLICY_CLASS);
    private static final Method CORRECT = method(V3ColumnCalculator.class, "correctCondenserPhase",
            PASS_CLASS, V3PengRobinsonThermo.class, V3SolveControl.class, POLICY_CLASS,
            V3ColumnCalculator.CondenserAttempts.class);
    private static final Method CORRECTED_PASS = method(CORRECTION_CLASS, "pass");
    private static final Method PASS_PREPARED = method(PASS_CLASS, "prepared");
    private static final Method PASS_ATTEMPT = method(PASS_CLASS, "attempt");
    private static final Method PASS_AUDIT = method(PASS_CLASS, "audit");
    private static final Method PASS_FEED_ENTHALPY = method(PASS_CLASS, "feedMolarEnthalpyJoulesPerMol");
    private static final Method PASS_PATH = method(PASS_CLASS, "solvePath");
    private static final Method PASS_RECOVERY_SEED = method(PASS_CLASS, "recoverySeed");
    private static final Method PREPARED_PROBLEM = method(PREPARED_CLASS, "problem");
    private static final Method PREPARED_SEED = method(PREPARED_CLASS, "seed");

    private V3PressureContinuationAccess() {}

    /** One diagnostic chain using the original request's exact inputs and one fixed truncation policy. */
    public static final class Session {
        private final V3ColumnInput originalInput;
        private final V3SolveControl control;
        private final Object policy;
        private final V3PengRobinsonThermo thermo;
        private final V3CondenserPhaseBranch preferredBranch;
        private final String pressurePath;
        private V3ColumnCalculator.CondenserAttempts condenserAttempts;

        /** Preserves the existing truncation-OFF diagnostic path. */
        public Session(V3ColumnInput originalInput, V3SolveControl control) {
            this(originalInput, control, OFF);
        }

        /** Runs the requested cutoff without invoking the outer whole-chain untruncated fallback dispatcher. */
        public Session(V3ColumnInput originalInput, V3SolveControl control, double requestedCutoff) {
            this(originalInput, control, truncationPolicy(requestedCutoff));
        }

        private Session(V3ColumnInput originalInput, V3SolveControl control, Object policy) {
            this.originalInput = Objects.requireNonNull(originalInput, "originalInput");
            this.control = Objects.requireNonNull(control, "control");
            this.policy = Objects.requireNonNull(policy, "policy");
            this.thermo = V3PengRobinsonThermo.fromRegisteredPackage(originalInput.packageId());
            this.preferredBranch = (V3CondenserPhaseBranch) invoke(PREFERRED, null, originalInput, control);
            this.pressurePath = (String) invoke(PRESSURE_PATH, null, originalInput.topPressurePascal());
            resetCondenserAttempts();
        }

        public V3PengRobinsonThermo thermo() { return thermo; }
        public V3ColumnInput originalInput() { return originalInput; }
        public V3CondenserPhaseBranch preferredBranch() { return preferredBranch; }

        /**
         * Executes the actual anchor/stage/pressure controller through the requested prefix, with fresh attempt
         * history. The preferred branch still comes from the original input, not this prefix's target pressure.
         * A failed prefix is returned as evidence; callers must check {@link CapturedPass#accepted()} before reuse.
         */
        public CapturedPass prefix(double pressurePascal) {
            resetCondenserAttempts();
            V3ColumnInput prefixInput = withPressure(pressurePascal);
            return capture(invoke(CONTINUATION, null, prefixInput, thermo, control, preferredBranch, policy, condenserAttempts));
        }

        /** Executes the actual single pressure-predictor path; phase correction remains an explicit separate call. */
        public CapturedPass predict(CapturedPass previous, double pressurePascal, int maximumIterations) {
            requireOwned(previous);
            V3ColumnProblem problem = nextProblem(previous, pressurePascal);
            return capture(invoke(PREDICT, null, problem, thermo, previous.state(), control,
                    stepPath(pressurePascal, false), PRESSURE_PREDICTOR, maximumIterations, policy));
        }

        /**
         * Executes the actual Wang-Henke projection/recovery path from the explicitly selected source state.
         * Production pressure recovery uses the original prior accepted pressure state, not the failed predictor.
         * Supplying another source is a caller-labelled diagnostic counterfactual, not the normal controller path.
         */
        public CapturedPass recover(
                CapturedPass previous, double pressurePascal, V3DryMeshState source, int maximumIterations) {
            requireOwned(previous);
            V3ColumnProblem problem = nextProblem(previous, pressurePascal);
            return capture(invoke(RECOVER, null, problem, thermo, Objects.requireNonNull(source, "source"), control,
                    stepPath(pressurePascal, true), PRESSURE_PREDICTOR, maximumIterations, policy));
        }

        /** Invokes the production conditional warm phase-correction gate, preserving this session's branch history. */
        public CapturedPass correct(CapturedPass pass) {
            requireOwned(pass);
            Object correction = invoke(CORRECT, null, pass.raw, thermo, control, policy, condenserAttempts);
            return capture(invoke(CORRECTED_PASS, correction));
        }

        private V3ColumnProblem nextProblem(CapturedPass previous, double pressurePascal) {
            return V3ColumnProblemResolver.resolve(withPressure(pressurePascal),
                    previous.problem().topology().condenserPhaseBranch());
        }

        private V3ColumnInput withPressure(double pressurePascal) {
            return (V3ColumnInput) invoke(WITH_PRESSURE, null, originalInput, pressurePascal);
        }

        private String stepPath(double pressurePascal, boolean recovery) {
            return "cold/dwsim-pressure/" + pressurePath + "/top-" + Math.round(pressurePascal / 1_000.0)
                    + "kpa/" + (recovery ? "material-vle-recovery/" : "") + "fine-fd";
        }

        private void resetCondenserAttempts() {
            condenserAttempts = new V3ColumnCalculator.CondenserAttempts();
            // calculateBranch performs this bookkeeping before entering the private continuation controller.
            condenserAttempts.recordAttempt(preferredBranch);
        }

        private void requireOwned(CapturedPass pass) {
            Objects.requireNonNull(pass, "pass");
            if (pass.owner != this) throw new IllegalArgumentException("Captured pass belongs to a different diagnostic session");
        }

        private CapturedPass capture(Object raw) { return new CapturedPass(this, raw); }
    }

    /** Immutable diagnostic view; raw reflection and session references are excluded from JSON serialization. */
    public static final class CapturedPass {
        private final transient Session owner;
        private final transient Object raw;
        private final V3ColumnProblem problem;
        private final V3DryMeshState seed;
        private final V3DryMeshState state;
        private final V3DryMeshState recoverySeed;
        private final V3SimultaneousColumnSolver.Attempt attempt;
        private final V3AcceptanceAudit audit;
        private final double feedEnthalpy;
        private final String path;

        private CapturedPass(Session owner, Object raw) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.raw = Objects.requireNonNull(raw, "raw");
            Object prepared = invoke(PASS_PREPARED, raw);
            this.problem = (V3ColumnProblem) invoke(PREPARED_PROBLEM, prepared);
            this.seed = (V3DryMeshState) invoke(PREPARED_SEED, prepared);
            this.attempt = (V3SimultaneousColumnSolver.Attempt) invoke(PASS_ATTEMPT, raw);
            this.state = attempt.state();
            this.recoverySeed = (V3DryMeshState) invoke(PASS_RECOVERY_SEED, raw);
            this.audit = (V3AcceptanceAudit) invoke(PASS_AUDIT, raw);
            this.feedEnthalpy = (double) invoke(PASS_FEED_ENTHALPY, raw);
            this.path = (String) invoke(PASS_PATH, raw);
        }

        public V3ColumnProblem problem() { return problem; }
        public V3DryMeshState seed() { return seed; }
        public V3DryMeshState state() { return state; }
        public V3DryMeshState recoverySeed() { return recoverySeed; }
        public V3SimultaneousColumnSolver.Attempt attempt() { return attempt; }
        public V3AcceptanceAudit audit() { return audit; }
        public double feedEnthalpy() { return feedEnthalpy; }
        public String path() { return path; }
        public boolean accepted() {
            return attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged
                    && attempt.evidence().convergenceEvidence().satisfiesGates() && audit.accepted();
        }
    }

    private static Class<?> nestedClass(String name) {
        try {
            return Class.forName(V3ColumnCalculator.class.getName() + "$" + name);
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException("Calculator diagnostic bridge no longer matches nested type " + name, missing);
        }
    }

    private static Method method(Class<?> declaringClass, String name, Class<?>... parameters) {
        try {
            Method method = declaringClass.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("Calculator diagnostic bridge no longer matches method " + name, missing);
        }
    }

    private static Object truncationPolicy(double requestedCutoff) {
        try {
            Constructor<?> constructor = POLICY_CLASS.getDeclaredConstructor(double.class, double.class);
            constructor.setAccessible(true);
            return constructor.newInstance(requestedCutoff, requestedCutoff);
        } catch (InvocationTargetException invocation) {
            Throwable cause = invocation.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Unexpected checked failure from calculator truncation policy", cause);
        } catch (ReflectiveOperationException inaccessible) {
            throw new IllegalStateException("Could not construct calculator diagnostic truncation policy", inaccessible);
        }
    }

    private static Object staticField(Class<?> declaringClass, String name) {
        try {
            Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("Calculator diagnostic bridge no longer matches field " + name, missing);
        }
    }

    private static Object enumConstant(Class<?> enumClass, String name) {
        for (Object value : enumClass.getEnumConstants()) {
            if (((Enum<?>) value).name().equals(name)) return value;
        }
        throw new IllegalStateException("Calculator diagnostic bridge no longer matches enum constant " + name);
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException invocation) {
            Throwable cause = invocation.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Unexpected checked failure from calculator diagnostic invocation", cause);
        } catch (ReflectiveOperationException inaccessible) {
            throw new IllegalStateException("Could not invoke calculator diagnostic method " + method.getName(), inaccessible);
        }
    }
}

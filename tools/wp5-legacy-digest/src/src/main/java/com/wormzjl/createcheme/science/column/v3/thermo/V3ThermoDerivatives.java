package com.wormzjl.createcheme.science.column.v3.thermo;

/**
 * The optional capability of a {@link V3ThermoModel} to differentiate itself.
 *
 * <p>A Newton block for one equilibrium stage is built from exactly these derivatives, and a model that
 * supplies them replaces two property evaluations per unknown with one per phase. Implementing this is a
 * promise about the branch, not only about the numbers: the derivatives must belong to the same selected root,
 * the same coalescence policy and the same domain checks as {@link V3ThermoModel#fugacity}, so that they
 * differentiate the very function whose values the residual carries. Where that promise cannot be kept — at a
 * root coalescence, where the derivative does not exist — the implementation throws exactly as the value
 * evaluation does, and the caller falls back to differencing.</p>
 *
 * <p>Not implementing this is a complete answer: every consumer probes instead.</p>
 */
public interface V3ThermoDerivatives {
    /**
     * Returns the first derivatives of the named phase at the same state {@link V3ThermoModel#fugacity} would
     * evaluate, on the model's public component axis.
     *
     * @throws V3ThermoException when the state is outside the property domain or the selected root is not
     *     separated enough for its derivative to exist
     */
    V3FugacityDerivatives fugacityDerivatives(
            double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
            V3ThermoWorkspace workspace);
}

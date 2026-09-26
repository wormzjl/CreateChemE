package com.wormzjl.createcheme.science.thermo.phase;

import java.util.Objects;

/**
 * A pure crystal of one species: the P2 solid-evaluator contract. The real solid CO2 model is P4; P2 fixes only its
 * shape and how it is anchored.
 *
 * <p>A pure solid is anchored at the triple point to the fluid model's chemical potential (plan section 4): the caller
 * evaluates the fluid family at the species' triple point and passes that chemical potential as a
 * {@link TriplePointAnchor}; the solid model contributes everything else from its own record (its entropy or enthalpy
 * of transition at the anchor, heat capacity, volume, expansion and compressibility). Melting, sublimation and the
 * triple point then agree with the fluid family by construction, and a change of the fluid family moves the solid with
 * it: the anchor is part of the result's thermodynamic identity through the fluid family.</p>
 *
 * <p>The state is filled as {@link PhaseKind#SOLID} with {@link #crystal()}, over the one-component basis of
 * {@link #component()}, with {@code ln phi = (g_s - g_ig(T, P))/(R T)} against the species' ideal-gas function, so its
 * {@link PhaseState#chemicalPotential(int)} compares directly with a fluid phase's.</p>
 */
public interface SolidPhaseEvaluator {
    /**
     * The fluid chemical potential of the pure species at its triple point, J/mol on the spine's reference.
     *
     * @param component the species id; must equal the evaluator's {@link #component()}
     */
    record TriplePointAnchor(String component, double temperature, double pressure, double chemicalPotential) {
        public TriplePointAnchor {
            Objects.requireNonNull(component, "component");
            if (!(temperature > 0.0) || !(pressure > 0.0) || !Double.isFinite(temperature) || !Double.isFinite(pressure)
                    || !Double.isFinite(chemicalPotential)) {
                throw new IllegalArgumentException("A triple-point anchor needs a finite state and chemical potential");
            }
        }
    }

    /** Solid-model family id, part of the thermodynamic identity of a package that admits the crystal. */
    String family();

    /** The crystal id, e.g. {@code co2_solid_i}. */
    String crystal();

    /** The species the crystal is made of, a component id of the shared basis. */
    String component();

    /**
     * Fills {@code out} (one component) with the crystal at {@code (temperature, pressure)}; its Gibbs energy at the
     * anchor's state equals the anchor's chemical potential.
     */
    PhaseState evaluate(double temperature, double pressure, TriplePointAnchor anchor, PhaseState out);

    /** {@code g_s(T, P)}, J/mol. */
    default double chemicalPotential(double temperature, double pressure, TriplePointAnchor anchor) {
        return evaluate(temperature, pressure, anchor, new PhaseState(1)).molarGibbsEnergy();
    }
}

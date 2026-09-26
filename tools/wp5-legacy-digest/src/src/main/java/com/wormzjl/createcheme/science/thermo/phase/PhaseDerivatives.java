package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.thermo.phase.PhaseEvaluator.Capability;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * First derivatives of one phase at fixed composition, with the {@link PhaseState} they were taken at (the same root).
 *
 * <p>Caller-owned and refilled per evaluation, like {@link PhaseState}. Each block is present only when the family that
 * filled it declares the matching {@link Capability}; reading a block the family did not provide throws
 * {@link UnsupportedOperationException} instead of returning a stale or zero number.</p>
 *
 * <p>Conventions: {@code d ln phi_i / d n_j} at the evaluated composition normalised to one mole (the kernel's), at
 * constant T and P; {@code d ln phi_i / dT} at constant P and composition, {@code d ln phi_i / dP} at constant T and
 * composition; {@code dv/dT}, {@code dv/dP}, {@code dh/dT} (the whole {@code c_p}, ideal-gas part included) and
 * {@code dh/dP} of the molar quantities at constant composition.</p>
 */
public final class PhaseDerivatives {
    private final PhaseState state;
    private final double[][] logFugacityComposition;
    private final double[] logFugacityTemperature;
    private final double[] logFugacityPressure;
    private final EnumSet<Capability> supported = EnumSet.noneOf(Capability.class);
    private String family = "";
    private double volumeTemperature;
    private double volumePressure;
    private double enthalpyTemperature;
    private double enthalpyPressure;

    public PhaseDerivatives(int componentCount) {
        state = new PhaseState(componentCount);
        logFugacityComposition = new double[componentCount][componentCount];
        logFugacityTemperature = new double[componentCount];
        logFugacityPressure = new double[componentCount];
    }

    /** The phase these derivatives describe, evaluated on the same root. */
    public PhaseState state() { return state; }
    public int componentCount() { return logFugacityTemperature.length; }
    /** The blocks the last fill provided. */
    public Set<Capability> supported() { return EnumSet.copyOf(supported); }
    public boolean supports(Capability capability) { return supported.contains(capability); }
    public String family() { return family; }

    public double logFugacityCompositionDerivative(int component, int respectTo) {
        require(Capability.FUGACITY_COMPOSITION_DERIVATIVES);
        return logFugacityComposition[component][respectTo];
    }
    /** The row itself; the caller must not mutate it. */
    public double[] logFugacityCompositionRowView(int component) {
        require(Capability.FUGACITY_COMPOSITION_DERIVATIVES);
        return logFugacityComposition[component];
    }
    public double[] logFugacityTemperatureDerivativeView() {
        require(Capability.FUGACITY_STATE_DERIVATIVES);
        return logFugacityTemperature;
    }
    public double[] logFugacityPressureDerivativeView() {
        require(Capability.FUGACITY_STATE_DERIVATIVES);
        return logFugacityPressure;
    }
    /** m3/(mol K). */
    public double volumeTemperatureDerivative() { require(Capability.VOLUMETRIC_DERIVATIVES); return volumeTemperature; }
    /** m3/(mol Pa). */
    public double volumePressureDerivative() { require(Capability.VOLUMETRIC_DERIVATIVES); return volumePressure; }
    /** {@code c_p}, J/(mol K), ideal-gas part included. */
    public double enthalpyTemperatureDerivative() { require(Capability.CALORIC_DERIVATIVES); return enthalpyTemperature; }
    /** m3/mol ({@code J/(mol Pa)}). */
    public double enthalpyPressureDerivative() { require(Capability.CALORIC_DERIVATIVES); return enthalpyPressure; }

    // Filling, for an evaluator. Nothing here allocates.

    /** Starts a fill by {@code family}: every block becomes unavailable until it is set again. */
    public void begin(String family) {
        this.family = Objects.requireNonNull(family, "family");
        supported.clear();
    }
    /** Writable row buffer for {@code d ln phi_i / d n_j}; call {@link #provide} with the capability once filled. */
    public double[] logFugacityCompositionRowBuffer(int component) { return logFugacityComposition[component]; }
    public double[] logFugacityTemperatureBuffer() { return logFugacityTemperature; }
    public double[] logFugacityPressureBuffer() { return logFugacityPressure; }
    public void setVolumetric(double volumeTemperatureDerivative, double volumePressureDerivative) {
        volumeTemperature = volumeTemperatureDerivative;
        volumePressure = volumePressureDerivative;
        supported.add(Capability.VOLUMETRIC_DERIVATIVES);
    }
    public void setCaloric(double enthalpyTemperatureDerivative, double enthalpyPressureDerivative) {
        enthalpyTemperature = enthalpyTemperatureDerivative;
        enthalpyPressure = enthalpyPressureDerivative;
        supported.add(Capability.CALORIC_DERIVATIVES);
    }
    /** Declares a buffer-filled block ({@link Capability#FUGACITY_COMPOSITION_DERIVATIVES} or the state one) valid. */
    public void provide(Capability capability) { supported.add(Objects.requireNonNull(capability)); }

    private void require(Capability capability) {
        if (!supported.contains(capability)) {
            throw new UnsupportedOperationException("Evaluator family '" + family + "' did not provide " + capability);
        }
    }
}

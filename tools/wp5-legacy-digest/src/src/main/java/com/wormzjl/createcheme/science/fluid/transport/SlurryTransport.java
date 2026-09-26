package com.wormzjl.createcheme.science.fluid.transport;

/**
 * SI transport correlations for inert particles in a liquid carrier.
 * These functions do not own material or decide which conserved populations are traces.
 */
public final class SlurryTransport {
    public static final double MAXIMUM_PACKING_FRACTION = 0.62;
    public static final double DEFAULT_SUSPENSION_MULTIPLIER = 10;
    public static final double DEFAULT_TRACE_VOLUME_FRACTION = 1e-8;
    public static final double DEFAULT_IMMOBILE_VISCOSITY = 100;
    private static final double GRAVITY = 9.80665;

    private SlurryTransport() {}

    /**
     * Krieger-Dougherty viscosity for spherical particles. The input fraction excludes vapor.
     * Packed states are not flowing states; the caller must close transport before evaluating them.
     */
    public static double effectiveViscosity(double carrierViscosity, double solidVolumeFraction) {
        positive(carrierViscosity, "carrier viscosity");
        if (!Double.isFinite(solidVolumeFraction) || solidVolumeFraction < 0
                || solidVolumeFraction >= MAXIMUM_PACKING_FRACTION) {
            throw new IllegalArgumentException("Slurry volume fraction outside the flowing domain");
        }
        if (solidVolumeFraction == 0) return carrierViscosity;
        return positive(carrierViscosity * Math.pow(1 - solidVolumeFraction / MAXIMUM_PACKING_FRACTION,
                -2.5 * MAXIMUM_PACKING_FRACTION), "slurry viscosity");
    }

    /**
     * Ferguson-Church terminal speed, C1=18 and C2=0.4 (smooth spheres).
     * The absolute density contrast also models rising particles; that extension is a gameplay rule.
     * Diameter zero denotes an immobile bulk phase elsewhere and is deliberately rejected here.
     */
    public static double settlingSpeed(double diameter, double particleDensity,
                                       double carrierDensity, double carrierViscosity) {
        positive(diameter, "particle diameter");
        positive(particleDensity, "particle density");
        positive(carrierDensity, "carrier density");
        positive(carrierViscosity, "carrier viscosity");
        double contrast = Math.abs(particleDensity / carrierDensity - 1);
        if (contrast == 0) return 0;
        double kinematicViscosity = carrierViscosity / carrierDensity;
        double buoyancy = contrast * GRAVITY;
        double speed = buoyancy * diameter * diameter
                / (18 * kinematicViscosity + Math.sqrt(0.3 * buoyancy * diameter * diameter * diameter));
        return positive(speed, "settling speed");
    }

    /** A calibrated multiplier is not claimed: this is the chosen simplified suspension rule. */
    public static double depositionVelocity(double diameter, double particleDensity,
                                            double carrierDensity, double carrierViscosity,
                                            double suspensionMultiplier) {
        positive(suspensionMultiplier, "suspension multiplier");
        double result = suspensionMultiplier * settlingSpeed(diameter, particleDensity, carrierDensity, carrierViscosity);
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Nonfinite deposition velocity");
        return result;
    }

    /** The agreed cutoff is strict: a phase exactly at the threshold remains mobile. */
    public static boolean immobile(double phaseViscosity, double threshold) {
        positive(phaseViscosity, "phase viscosity");
        positive(threshold, "immobility threshold");
        return phaseViscosity > threshold;
    }

    /**
     * Whether a nonzero population affects blockage. Traces are still conserved and occupy slots.
     * The denominator is occupied mixture volume, including vapor. A zero cutoff disables masking.
     */
    public static boolean activeForBlockage(double populationVolume, double occupiedVolume, double cutoff) {
        positive(occupiedVolume, "occupied volume");
        if (!Double.isFinite(populationVolume) || populationVolume < 0 || populationVolume > occupiedVolume
                || !Double.isFinite(cutoff) || cutoff < 0 || cutoff >= 1) {
            throw new IllegalArgumentException("Invalid particle presence fraction");
        }
        return populationVolume > 0 && (cutoff == 0 || populationVolume / occupiedVolume >= cutoff);
    }

    private static double positive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Positive finite " + name + " required");
        return value;
    }
}
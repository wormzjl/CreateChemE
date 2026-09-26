package com.wormzjl.createcheme.science.thermo.phase;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Objects;

/**
 * The thermodynamic identity of a package as the phase engine evaluates it: everything that decides a phase state's
 * numbers. Two results with the same {@link #revision()} come from the same data and the same formulation.
 *
 * <p>Parts:</p>
 * <ul>
 *   <li>{@code packageFingerprint}: the package's data (components, PR constants, interactions, the volume references
 *   the translation is calibrated from, the declared domains); {@link #of} takes
 *   {@link MaterialCatalog#fluidThermoFingerprint(String)}.</li>
 *   <li>{@code evaluatorFamily}: the residual formulation, {@link PhaseEvaluator#family()}.</li>
 *   <li>{@code spineFingerprint}: the ideal-gas and formation data (the data spine, decision D6). The P2 data work adds
 *   it to the package; until a package carries one the caller passes {@link #NO_SPINE}.</li>
 *   <li>{@code waterModelRevision}: the water model and how it participates ({@link WaterParticipation}).</li>
 * </ul>
 *
 * <p>Cache rule: anything that stores a number computed from a phase state, or a decision derived from one, keys on
 * {@link #revision()} and is discarded, never migrated, when it changes (the owner's no-compatibility rule): the fluid
 * network's thermodynamic revision ({@code ApproximationAnchor.thermodynamicRevision}, which saved islands are checked
 * against), the column's neural-model physics fingerprint and its eligibility check, regression fixtures and pins, and
 * any approximation state (anchors, warm starts, factorizations) built on the phase engine.</p>
 *
 * <p>The revision encodes every part length-prefixed, so no two different identities share a revision whatever
 * characters the parts contain.</p>
 */
public final class ThermoIdentity {
    /** The spine fingerprint of a package that does not declare one yet. */
    public static final String NO_SPINE = "spine:none";
    /** The water model revision of a package without water. */
    public static final String NO_WATER = "water:none";
    /** The formulation label of the separate free-water approximation; bumped when its equations change. */
    public static final String SEPARATE_FREE_WATER_FORMULATION = "separate-free-water-v1";
    private static final String FORMAT = "thermo-identity-v1";

    private final String packageFingerprint;
    private final String evaluatorFamily;
    private final String spineFingerprint;
    private final String waterModelRevision;
    private final String revision;

    public ThermoIdentity(String packageFingerprint, String evaluatorFamily, String spineFingerprint,
                          String waterModelRevision) {
        this.packageFingerprint = requirePart(packageFingerprint, "packageFingerprint");
        this.evaluatorFamily = requirePart(evaluatorFamily, "evaluatorFamily");
        this.spineFingerprint = requirePart(spineFingerprint, "spineFingerprint");
        this.waterModelRevision = requirePart(waterModelRevision, "waterModelRevision");
        this.revision = FORMAT + part(this.packageFingerprint) + part(this.evaluatorFamily) + part(this.spineFingerprint)
                + part(this.waterModelRevision);
    }

    /** The identity of a catalog package evaluated by {@code evaluatorFamily} with {@code water} participation. */
    public static ThermoIdentity of(MaterialCatalog catalog, String packageId, String evaluatorFamily,
                                    String spineFingerprint, WaterParticipation water) {
        Objects.requireNonNull(water, "water");
        MaterialCatalog.Package propertyPackage = catalog.requirePackage(packageId);
        String waterRevision = water == WaterParticipation.NONE ? NO_WATER
                : SEPARATE_FREE_WATER_FORMULATION + ":" + propertyPackage.water().revision();
        return new ThermoIdentity(catalog.fluidThermoFingerprint(packageId), evaluatorFamily, spineFingerprint, waterRevision);
    }

    public String packageFingerprint() { return packageFingerprint; }
    public String evaluatorFamily() { return evaluatorFamily; }
    public String spineFingerprint() { return spineFingerprint; }
    public String waterModelRevision() { return waterModelRevision; }
    /** The cache key; see the class comment. */
    public String revision() { return revision; }

    @Override public boolean equals(Object other) {
        return other instanceof ThermoIdentity identity && revision.equals(identity.revision);
    }
    @Override public int hashCode() { return revision.hashCode(); }
    @Override public String toString() { return revision; }

    private static String requirePart(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String part(String value) { return ";" + value.length() + ":" + value; }
}

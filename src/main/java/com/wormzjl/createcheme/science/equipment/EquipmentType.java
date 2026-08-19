package com.wormzjl.createcheme.science.equipment;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stable scientific identities for the process equipment in the planned base game.
 *
 * <p>The serialized names are append-only data and protocol identifiers. They deliberately do not depend on enum
 * order, Minecraft registries, or display text. Scientific solvers and game adapters should dispatch through these
 * identities rather than duplicate a switch in every layer.</p>
 */
public enum EquipmentType {
    STORAGE_DRUM("storage_drum", Family.STORAGE),
    PUMP("pump", Family.ROTATING),
    COMPRESSOR("compressor", Family.ROTATING),
    HEAT_EXCHANGER("heat_exchanger", Family.THERMAL),
    BOILER("boiler", Family.THERMAL),
    FURNACE("furnace", Family.THERMAL),
    REACTOR("reactor", Family.REACTION),
    GAS_LIQUID_SEPARATOR("gas_liquid_separator", Family.SEPARATION),
    THREE_PHASE_SEPARATOR("three_phase_separator", Family.SEPARATION),
    AIR_COOLER("air_cooler", Family.THERMAL),
    DISTILLATION_COLUMN("distillation_column", Family.SEPARATION),
    PRESSURE_SWING_ADSORBER("pressure_swing_adsorber", Family.ADSORPTION),
    STIRRED_TANK_REACTOR("stirred_tank_reactor", Family.REACTION);

    private static final Map<String, EquipmentType> BY_SERIALIZED_NAME = buildLookup();

    private final String serializedName;
    private final Family family;

    EquipmentType(String serializedName, Family family) {
        this.serializedName = serializedName;
        this.family = family;
    }

    public String serializedName() {
        return serializedName;
    }

    public Family family() {
        return family;
    }

    public static Optional<EquipmentType> fromSerializedName(String serializedName) {
        return Optional.ofNullable(BY_SERIALIZED_NAME.get(serializedName));
    }

    private static Map<String, EquipmentType> buildLookup() {
        Map<String, EquipmentType> valuesByName = new HashMap<>();
        for (EquipmentType type : values()) {
            EquipmentType previous = valuesByName.put(type.serializedName, type);
            if (previous != null) {
                throw new IllegalStateException("Duplicate equipment serialized name: " + type.serializedName);
            }
        }
        return Collections.unmodifiableMap(valuesByName);
    }

    /** Broad implementation families which share equations and game infrastructure. */
    public enum Family {
        STORAGE,
        ROTATING,
        THERMAL,
        REACTION,
        SEPARATION,
        ADSORPTION
    }
}

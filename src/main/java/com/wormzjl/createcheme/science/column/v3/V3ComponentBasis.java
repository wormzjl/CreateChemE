package com.wormzjl.createcheme.science.column.v3;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable ordered hydrocarbon axis used by the dry V3 contract. */
public final class V3ComponentBasis {
    public static final int MAX_COMPONENTS = 64;

    private final List<String> componentIds;

    public V3ComponentBasis(List<String> componentIds) {
        componentIds = List.copyOf(componentIds);
        if (componentIds.isEmpty() || componentIds.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException("A V3 component basis must contain 1.." + MAX_COMPONENTS + " components");
        }
        Set<String> uniqueIds = new HashSet<>();
        for (String componentId : componentIds) {
            if (!isIdentifier(componentId) || !uniqueIds.add(componentId)) {
                throw new IllegalArgumentException("V3 component IDs must be unique stable identifiers");
            }
        }
        this.componentIds = componentIds;
    }

    public int componentCount() {
        return componentIds.size();
    }

    public String componentId(int component) {
        return componentIds.get(component);
    }

    public List<String> componentIds() {
        return componentIds;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof V3ComponentBasis basis && componentIds.equals(basis.componentIds);
    }

    @Override
    public int hashCode() {
        return componentIds.hashCode();
    }

    @Override
    public String toString() {
        return "V3ComponentBasis" + componentIds;
    }

    private static boolean isIdentifier(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_.:-]{0,63}");
    }
}

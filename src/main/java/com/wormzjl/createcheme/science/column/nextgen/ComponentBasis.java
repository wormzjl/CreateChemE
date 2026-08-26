package com.wormzjl.createcheme.science.column.nextgen;

import java.util.List;
import java.util.Objects;

/** Stable package-owned public component axis; the CDU uses 16 hydrocarbons followed by water. */
public record ComponentBasis(List<ComponentDescriptor> components, int hydrocarbonCount, int waterIndex) {
    public ComponentBasis {
        components = List.copyOf(components);
        if (components.isEmpty() || hydrocarbonCount < 1 || hydrocarbonCount > components.size()
                || waterIndex < -1 || waterIndex >= components.size() || components.stream().anyMatch(Objects::isNull)
                || components.subList(0, hydrocarbonCount).stream().anyMatch(component -> !component.hydrocarbon())
                || (waterIndex >= 0 && components.get(waterIndex).hydrocarbon())) {
            throw new IllegalArgumentException("Invalid package component axis");
        }
    }

    public ComponentDescriptor hydrocarbon(int index) {
        if (index < 0 || index >= hydrocarbonCount) throw new IndexOutOfBoundsException(index);
        return components.get(index);
    }

    public List<String> publicAxisIds() {
        return components.stream().map(ComponentDescriptor::id).toList();
    }
}

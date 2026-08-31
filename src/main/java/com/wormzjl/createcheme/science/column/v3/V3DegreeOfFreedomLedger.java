package com.wormzjl.createcheme.science.column.v3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Independently enumerates the V3 unknowns, residuals, controls, and structural-rank evidence.
 *
 * <p>This is intentionally a topology/contract artifact.  It does not assemble production
 * residual values or use a numerical solver.</p>
 */
public final class V3DegreeOfFreedomLedger {
    private final V3ColumnTopology topology;
    private final int componentCount;
    private final List<V3ColumnSpecification> specifications;
    private final List<Unknown> unknowns;
    private final List<Equation> equations;
    private final List<V3CalculatedQuantity> calculatedQuantities;
    private final List<V3ContractDiagnostic> diagnostics;
    private final int structuralRank;

    private V3DegreeOfFreedomLedger(
            V3ColumnTopology topology, int componentCount, List<V3ColumnSpecification> specifications,
            List<Unknown> unknowns, List<Equation> equations, List<V3CalculatedQuantity> calculatedQuantities,
            List<V3ContractDiagnostic> diagnostics, int structuralRank) {
        this.topology = topology;
        this.componentCount = componentCount;
        this.specifications = List.copyOf(specifications);
        this.unknowns = List.copyOf(unknowns);
        this.equations = List.copyOf(equations);
        this.calculatedQuantities = List.copyOf(calculatedQuantities);
        this.diagnostics = List.copyOf(diagnostics);
        this.structuralRank = structuralRank;
    }

    public static V3DegreeOfFreedomLedger create(
            V3ColumnTopology topology, int componentCount, List<V3ColumnSpecification> specifications) {
        V3CondenserComponentPhases phases = V3CondenserComponentPhases.allLiquid(componentCount);
        return create(topology, componentCount, specifications, phases);
    }

    static V3DegreeOfFreedomLedger create(
            V3ColumnTopology topology,
            int componentCount,
            List<V3ColumnSpecification> specifications,
            V3CondenserComponentPhases condenserComponentPhases) {
        topology = Objects.requireNonNull(topology, "topology");
        condenserComponentPhases = Objects.requireNonNull(condenserComponentPhases, "condenserComponentPhases");
        if (componentCount < 1 || componentCount > V3ComponentBasis.MAX_COMPONENTS) {
            throw new IllegalArgumentException("V3 component count is outside the supported contract range");
        }
        specifications = List.copyOf(Objects.requireNonNull(specifications, "specifications"));
        if (specifications.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("V3 specifications cannot contain null");
        }

        List<V3ContractDiagnostic> diagnostics = specificationDiagnostics(topology, specifications);
        List<Unknown> unknowns = enumerateUnknowns(topology, componentCount, condenserComponentPhases);
        List<Equation> equations = enumerateEquations(topology, componentCount, unknowns, diagnostics,
                condenserComponentPhases);
        int structuralRank = maximumBipartiteMatching(equations, unknowns);
        if (unknowns.size() != equations.size()) {
            diagnostics.add(new V3ContractDiagnostic("DOF_COUNT_MISMATCH", "V3 unknown and equation counts differ"));
        }
        if (structuralRank != equations.size()) {
            diagnostics.add(new V3ContractDiagnostic("STRUCTURAL_RANK_DEFICIENT",
                    "V3 equation-to-unknown sparsity matching is rank deficient"));
        }
        return new V3DegreeOfFreedomLedger(topology, componentCount, specifications, unknowns, equations,
                List.of(V3CalculatedQuantity.CONDENSER_DUTY, V3CalculatedQuantity.EXTERNAL_OVERHEAD_COMPONENT_FLOWS,
                        V3CalculatedQuantity.BOTTOMS_COMPONENT_FLOWS,
                        V3CalculatedQuantity.STAGE_LIQUID_COMPONENT_FLOWS,
                        V3CalculatedQuantity.STAGE_VAPOR_COMPONENT_FLOWS),
                diagnostics, structuralRank);
    }

    public V3ColumnTopology topology() {
        return topology;
    }

    public int componentCount() {
        return componentCount;
    }

    public List<V3ColumnSpecification> specifications() {
        return specifications;
    }

    public List<Unknown> unknowns() {
        return unknowns;
    }

    public List<Equation> equations() {
        return equations;
    }

    public List<V3CalculatedQuantity> calculatedQuantities() {
        return calculatedQuantities;
    }

    public List<V3ContractDiagnostic> diagnostics() {
        return diagnostics;
    }

    public int unknownCount() {
        return unknowns.size();
    }

    public int equationCount() {
        return equations.size();
    }

    public int structuralRank() {
        return structuralRank;
    }

    public boolean hasFullStructuralRank() {
        return unknownCount() == equationCount() && structuralRank == equationCount();
    }

    public boolean isValid() {
        return diagnostics.isEmpty() && hasFullStructuralRank();
    }

    /** Stable compact diagnostic intended for tests and a future V3 UI. */
    public String humanReadableDiagnostic() {
        String codes = diagnostics.isEmpty() ? "none"
                : diagnostics.stream().map(V3ContractDiagnostic::code).distinct().sorted().reduce((a, b) -> a + "," + b)
                .orElseThrow();
        return "topology=" + topology + ", components=" + componentCount + ", unknowns=" + unknownCount()
                + ", equations=" + equationCount() + ", structuralRank=" + structuralRank + ", diagnostics=" + codes;
    }

    private static List<V3ContractDiagnostic> specificationDiagnostics(
            V3ColumnTopology topology, List<V3ColumnSpecification> specifications) {
        Map<V3ControlledQuantity, V3ColumnSpecification> controls = new EnumMap<>(V3ControlledQuantity.class);
        List<V3ContractDiagnostic> diagnostics = new ArrayList<>();
        for (V3ColumnSpecification specification : specifications) {
            if (controls.putIfAbsent(specification.controlledQuantity(), specification) != null) {
                diagnostics.add(new V3ContractDiagnostic("DUPLICATE_CONTROL",
                        "V3 controls " + specification.controlledQuantity() + " more than once"));
            }
        }
        for (V3ControlledQuantity required : EnumSet.allOf(V3ControlledQuantity.class)) {
            if (!controls.containsKey(required)) {
                diagnostics.add(new V3ContractDiagnostic("MISSING_CONTROL", "V3 is missing " + required));
            }
        }
        V3ColumnSpecification reflux = controls.get(V3ControlledQuantity.ORGANIC_REFLUX_RATIO);
        if (topology.condenserPhaseBranch() == V3CondenserPhaseBranch.VAPOR_ONLY
                && reflux instanceof V3ColumnSpecification.OrganicRefluxRatio ratio && ratio.ratio() > 0.0) {
            diagnostics.add(new V3ContractDiagnostic("VAPOR_ONLY_WITH_POSITIVE_REFLUX",
                    "A vapor-only condenser cannot supply positive organic reflux"));
        }
        return diagnostics;
    }

    private static List<Unknown> enumerateUnknowns(
            V3ColumnTopology topology, int componentCount, V3CondenserComponentPhases condenserComponentPhases) {
        List<Unknown> unknowns = new ArrayList<>();
        for (int node = 0; node < topology.nodeCount(); node++) {
            for (int component = 0; component < componentCount; component++) {
                if (condenserComponentPhases.hasLiquid(topology, node, component)) {
                    unknowns.add(new Unknown(new UnknownId(UnknownFamily.LIQUID_COMPONENT_FLOW, node, component)));
                }
                if (topology.hasVaporPhase(node)) {
                    unknowns.add(new Unknown(new UnknownId(UnknownFamily.VAPOR_COMPONENT_FLOW, node, component)));
                }
            }
            if (topology.hasTemperatureUnknown(node)) {
                unknowns.add(new Unknown(new UnknownId(UnknownFamily.TEMPERATURE, node, -1)));
            }
        }
        return unknowns;
    }

    private static List<Equation> enumerateEquations(
            V3ColumnTopology topology, int componentCount, List<Unknown> unknowns,
            List<V3ContractDiagnostic> diagnostics, V3CondenserComponentPhases condenserComponentPhases) {
        Set<UnknownId> activeUnknowns = new HashSet<>(unknowns.stream().map(Unknown::id).toList());
        List<Equation> equations = new ArrayList<>();
        for (int node = 0; node < topology.nodeCount(); node++) {
            for (int component = 0; component < componentCount; component++) {
                equations.add(new Equation(new EquationId(EquationFamily.COMPONENT_MATERIAL_BALANCE, node, component),
                        materialReferences(topology, node, component, activeUnknowns)));
                if (condenserComponentPhases.hasVaporLiquidEquilibrium(topology, node, component)) {
                    equations.add(new Equation(new EquationId(EquationFamily.VAPOR_LIQUID_EQUILIBRIUM, node, component),
                            equilibriumReferences(topology, node, component, componentCount, activeUnknowns)));
                }
            }
            if (topology.hasEnergyEquation(node)) {
                equations.add(new Equation(new EquationId(EquationFamily.ENERGY_BALANCE, node, -1),
                        energyReferences(topology, node, componentCount, activeUnknowns)));
            }
        }
        for (Equation equation : equations) {
            if (equation.referencedUnknowns().isEmpty()) {
                diagnostics.add(new V3ContractDiagnostic("EQUATION_WITHOUT_UNKNOWN",
                        "V3 equation " + equation.id() + " does not reference an active unknown"));
            }
            for (UnknownId referencedUnknown : equation.referencedUnknowns()) {
                if (!activeUnknowns.contains(referencedUnknown)) {
                    diagnostics.add(new V3ContractDiagnostic("EQUATION_REFERENCES_REMOVED_PHASE",
                            "V3 equation " + equation.id() + " references " + referencedUnknown));
                }
            }
        }
        return equations;
    }

    private static List<UnknownId> materialReferences(
            V3ColumnTopology topology, int node, int component, Set<UnknownId> activeUnknowns) {
        Set<UnknownId> references = new HashSet<>();
        addIfActive(references, activeUnknowns, UnknownFamily.LIQUID_COMPONENT_FLOW, node, component);
        addIfActive(references, activeUnknowns, UnknownFamily.VAPOR_COMPONENT_FLOW, node, component);
        if (node > topology.condenserNode()) {
            addIfActive(references, activeUnknowns, UnknownFamily.LIQUID_COMPONENT_FLOW, node - 1, component);
        }
        if (node < topology.reboilerNode()) {
            addIfActive(references, activeUnknowns, UnknownFamily.VAPOR_COMPONENT_FLOW, node + 1, component);
        }
        return ordered(references);
    }

    private static List<UnknownId> equilibriumReferences(
            V3ColumnTopology topology, int node, int component, int componentCount, Set<UnknownId> activeUnknowns) {
        Set<UnknownId> references = new HashSet<>();
        for (int phaseComponent = 0; phaseComponent < componentCount; phaseComponent++) {
            addIfActive(references, activeUnknowns, UnknownFamily.LIQUID_COMPONENT_FLOW, node, phaseComponent);
            addIfActive(references, activeUnknowns, UnknownFamily.VAPOR_COMPONENT_FLOW, node, phaseComponent);
        }
        addIfActive(references, activeUnknowns, UnknownFamily.TEMPERATURE, node, -1);
        return ordered(references);
    }

    private static List<UnknownId> energyReferences(
            V3ColumnTopology topology, int node, int componentCount, Set<UnknownId> activeUnknowns) {
        Set<UnknownId> references = new HashSet<>();
        for (int relatedNode = Math.max(topology.condenserNode(), node - 1);
                relatedNode <= Math.min(topology.reboilerNode(), node + 1); relatedNode++) {
            for (int component = 0; component < componentCount; component++) {
                addIfActive(references, activeUnknowns, UnknownFamily.LIQUID_COMPONENT_FLOW, relatedNode, component);
                addIfActive(references, activeUnknowns, UnknownFamily.VAPOR_COMPONENT_FLOW, relatedNode, component);
            }
        }
        addIfActive(references, activeUnknowns, UnknownFamily.TEMPERATURE, node, -1);
        return ordered(references);
    }

    private static void addIfActive(
            Set<UnknownId> references, Set<UnknownId> activeUnknowns, UnknownFamily family, int node, int component) {
        UnknownId candidate = new UnknownId(family, node, component);
        if (activeUnknowns.contains(candidate)) references.add(candidate);
    }

    private static List<UnknownId> ordered(Set<UnknownId> references) {
        return references.stream().sorted().toList();
    }

    private static int maximumBipartiteMatching(List<Equation> equations, List<Unknown> unknowns) {
        Map<UnknownId, Integer> unknownIndexes = new HashMap<>();
        for (int index = 0; index < unknowns.size(); index++) unknownIndexes.put(unknowns.get(index).id(), index);
        int[] equationForUnknown = new int[unknowns.size()];
        java.util.Arrays.fill(equationForUnknown, -1);
        int matched = 0;
        for (int equation = 0; equation < equations.size(); equation++) {
            if (augment(equation, equations, unknownIndexes, equationForUnknown, new boolean[unknowns.size()])) matched++;
        }
        return matched;
    }

    private static boolean augment(
            int equation, List<Equation> equations, Map<UnknownId, Integer> unknownIndexes,
            int[] equationForUnknown, boolean[] visitedUnknowns) {
        for (UnknownId unknownId : equations.get(equation).referencedUnknowns()) {
            int unknown = unknownIndexes.get(unknownId);
            if (visitedUnknowns[unknown]) continue;
            visitedUnknowns[unknown] = true;
            if (equationForUnknown[unknown] == -1
                    || augment(equationForUnknown[unknown], equations, unknownIndexes, equationForUnknown, visitedUnknowns)) {
                equationForUnknown[unknown] = equation;
                return true;
            }
        }
        return false;
    }

    /** Stable semantic identity for one numerical unknown. */
    public record UnknownId(UnknownFamily family, int node, int component) implements Comparable<UnknownId> {
        public UnknownId {
            family = Objects.requireNonNull(family, "family");
            if (node < 0 || component < -1 || (family == UnknownFamily.TEMPERATURE && component != -1)
                    || (family != UnknownFamily.TEMPERATURE && component < 0)) {
                throw new IllegalArgumentException("Invalid V3 unknown semantic ID");
            }
        }

        @Override
        public int compareTo(UnknownId other) {
            int familyComparison = family.compareTo(other.family);
            if (familyComparison != 0) return familyComparison;
            int nodeComparison = Integer.compare(node, other.node);
            return nodeComparison != 0 ? nodeComparison : Integer.compare(component, other.component);
        }
    }

    /** Stable semantic identity for one residual equation. */
    public record EquationId(EquationFamily family, int node, int component) {
        public EquationId {
            family = Objects.requireNonNull(family, "family");
            if (node < 0 || component < -1 || (family == EquationFamily.ENERGY_BALANCE && component != -1)
                    || (family != EquationFamily.ENERGY_BALANCE && component < 0)) {
                throw new IllegalArgumentException("Invalid V3 equation semantic ID");
            }
        }
    }

    /** One unknown with no numerical value attached. */
    public record Unknown(UnknownId id) {
        public Unknown {
            id = Objects.requireNonNull(id, "id");
        }
    }

    /** One residual row and its structural references to active unknowns. */
    public record Equation(EquationId id, List<UnknownId> referencedUnknowns) {
        public Equation {
            id = Objects.requireNonNull(id, "id");
            referencedUnknowns = List.copyOf(referencedUnknowns);
            if (referencedUnknowns.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("V3 equation references cannot contain null");
            }
        }
    }

    public enum UnknownFamily {
        LIQUID_COMPONENT_FLOW,
        VAPOR_COMPONENT_FLOW,
        TEMPERATURE
    }

    public enum EquationFamily {
        COMPONENT_MATERIAL_BALANCE,
        VAPOR_LIQUID_EQUILIBRIUM,
        ENERGY_BALANCE
    }
}

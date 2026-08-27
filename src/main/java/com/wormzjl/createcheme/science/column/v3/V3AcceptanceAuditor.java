package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Recomputes dry physical acceptance from a candidate state; it never accepts a solver-cached residual vector. */
final class V3AcceptanceAuditor {
    private static final double EQUILIBRIUM_LIMIT = 1.0e-8;

    private final V3ColumnProblem problem;
    private final V3ThermoModel thermo;
    private final double feedMolarEnthalpyJoulesPerMol;

    V3AcceptanceAuditor(V3ColumnProblem problem, V3ThermoModel thermo, double feedMolarEnthalpyJoulesPerMol) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.thermo = Objects.requireNonNull(thermo, "thermo");
        if (!problem.input().componentBasis().equals(thermo.componentBasis()) || !Double.isFinite(feedMolarEnthalpyJoulesPerMol)) {
            throw new IllegalArgumentException("V3 acceptance auditor does not match its problem and thermodynamic model");
        }
        this.feedMolarEnthalpyJoulesPerMol = feedMolarEnthalpyJoulesPerMol;
    }

    V3AcceptanceAudit audit(V3DryMeshState state, V3ThermoWorkspace workspace) {
        return audit(state, workspace, V3SolveControl.UNBOUNDED);
    }

    V3AcceptanceAudit audit(V3DryMeshState state, V3ThermoWorkspace workspace, V3SolveControl control) {
        state = Objects.requireNonNull(state, "state");
        workspace = Objects.requireNonNull(workspace, "workspace");
        control = Objects.requireNonNull(control, "control");
        control.checkpoint();
        V3MeshResidual residual = new V3MeshResidualEvaluator(problem, thermo, feedMolarEnthalpyJoulesPerMol)
                .evaluate(state, workspace);
        control.checkpoint();
        List<V3AcceptanceAudit.Check> checks = new ArrayList<>();
        checks.add(finitenessAndTopology(state));
        checks.add(maximumFamily(residual, V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE,
                "LOCAL_COMPONENT_BALANCE", 1.0));
        checks.add(maximumFamily(residual, V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM,
                "EQUILIBRIUM", EQUILIBRIUM_LIMIT));
        checks.add(maximumFamily(residual, V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE,
                "ENERGY_BALANCE", 1.0));
        return new V3AcceptanceAudit(checks);
    }

    private V3AcceptanceAudit.Check finitenessAndTopology(V3DryMeshState state) {
        boolean valid = true;
        for (int node = 0; node < state.nodeCount(); node++) {
            valid &= Double.isFinite(state.temperatureKelvin(node)) && state.temperatureKelvin(node) > 0.0;
            for (int component = 0; component < state.componentCount(); component++) {
                valid &= Double.isFinite(state.vaporFlow(node, component)) && state.vaporFlow(node, component) > 0.0;
                if (problem.condenserComponentPhases().hasLiquid(problem.topology(), node, component)) {
                    valid &= Double.isFinite(state.liquidFlow(node, component)) && state.liquidFlow(node, component) > 0.0;
                } else {
                    valid &= state.liquidFlow(node, component) == 0.0;
                }
            }
        }
        double value = valid ? 0.0 : 1.0;
        return valid ? V3AcceptanceAudit.Check.pass("FINITE_TOPOLOGY", value, 0.0, "all active flows and topology phases are finite")
                : V3AcceptanceAudit.Check.fail("FINITE_TOPOLOGY", value, 0.0, "candidate violates a finite flow or absent-phase invariant");
    }

    private static V3AcceptanceAudit.Check maximumFamily(
            V3MeshResidual residual, V3DegreeOfFreedomLedger.EquationFamily family, String auditFamily, double limit) {
        double maximum = 0.0;
        boolean found = false;
        for (V3MeshResidual.Row row : residual.rows()) {
            if (row.equation().family() != family) continue;
            found = true;
            maximum = Math.max(maximum, Math.abs(row.scaledValue()));
        }
        if (!found) return V3AcceptanceAudit.Check.fail(auditFamily, 1.0, limit, "required dry acceptance family is absent");
        return maximum <= limit ? V3AcceptanceAudit.Check.pass(auditFamily, maximum, limit, "fresh residual recomputation")
                : V3AcceptanceAudit.Check.fail(auditFamily, maximum, limit, "fresh residual recomputation exceeded its limit");
    }
}

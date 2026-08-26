# ADR 0001: V3 M0 partial-condenser contract

- Status: Proposed for maintainer review; executable M0/M1 contract
- Date: 2026-08-26
- Scope: V3 dry-hydrocarbon baseline only

## Decision

V3 uses an explicit partial condenser, equilibrium trays, and an equilibrium partial
reboiler.  The node numbering is fixed: node `0` is the condenser, external tray
numbers `1..S` are equilibrium trays from top to bottom, and node `S + 1` is the
partial reboiler.  `stageCount` therefore counts trays only.

Pressure is generated once from `topPressurePa` and a non-negative constant
`stagePressureDropPa`:

```text
P[0] = P[1] = Ptop
P[s] = Ptop + (s - 1) * deltaP, for s = 1..S
P[S + 1] = P[S]
```

No caller provides a redundant pressure-profile array.

The only M0 controls are a specified condenser outlet temperature, organic reflux
ratio, and reboiler duty.  Condenser duty and external product component flows are
calculated quantities.  The condenser temperature is prescribed, so it has no
Newton temperature unknown and no condenser energy residual.  Reboiler duty is a
prescribed term in the reboiler energy residual.  Reflux ratio controls the split of
calculated condensate; it is not a second product-flow specification.

For a dry two-phase condenser with `C` active hydrocarbon components and `N = S + 2`
nodes, the unknown/equation map is:

| Family | Unknowns | Equations |
| --- | ---: | ---: |
| liquid component flows | `C * N` | — |
| vapor component flows | `C * N` | — |
| tray/reboiler temperatures | `N - 1` | — |
| component material residuals | — | `C * N` |
| hydrocarbon VLE residuals | — | `C * N` |
| tray/reboiler energy residuals | — | `N - 1` |
| Total | `2*C*N + N - 1` | `2*C*N + N - 1` |

At zero reflux, a stable two-phase condenser retains this same map: its liquid is
entirely external product.  A thermodynamically established vapor-only condenser is
a distinct compiled topology.  It removes its `C` condenser liquid-flow unknowns
and its `C` condenser VLE equations; a positive reflux ratio is invalid for that
topology.

## Hand-audited 2-component, 4-tray baseline

For `C = 2`, `S = 4`, and `N = 6`, the positive-reflux (and zero-reflux two-phase)
branch has 29 unknowns and 29 equations:

```text
unknowns = 2 liquid components * 6 nodes
         + 2 vapor components  * 6 nodes
         + temperatures at nodes 1..5
         = 12 + 12 + 5 = 29

equations = 2 component balances * 6 nodes
          + 2 VLE residuals      * 6 nodes
          + energy residuals at nodes 1..5
          = 12 + 12 + 5 = 29
```

The vapor-only branch has 27 unknowns and 27 equations after the two condenser
liquid variables and two condenser VLE rows are removed.  The M0 ledger constructs
an equation-to-unknown sparsity graph and requires a maximum bipartite matching of
the full equation count; equality of the two totals alone is not accepted.

## Consequences

M0/M1 supports a generic, all-hydrocarbon component basis and no side draws, water,
or utility feeds.  Exact-zero component feed rates remain in the public basis now;
the later numerical active-basis map will eliminate them from log-flow coordinates
without adding balance floors.

Thermodynamic-package/BIP choices, hard thermo domains, DWSIM comparison tolerances,
runtime admission limits, persistence budgets, and the long-term product rollout
remain open M0 review decisions.  They are deliberately not encoded as numerical
defaults in this slice.  Maintainer approval of this ADR is required before M2
numerical/oracle work begins.

## Clean-room boundary

This contract is an independent equation and topology description.  It cites the
V3 implementation plan's DWSIM behavioral references but copies no DWSIM source or
implementation details.

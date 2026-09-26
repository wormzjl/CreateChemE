# V3 W-2/W-3 Trace-Pair Conditioning Review

Date: 2026-09-02  
Scope: follow-up to the W-1 live-activation rollback and the rejected `c42c397` trace-pair experiment.

## What was rejected

Commit `c42c397` identified pairs using a `<= 1e-3` local phase-fraction test and added
`max(1, H_ii)` to the normal-equation diagonal of **both** liquid and vapor log-flow coordinates.
It was active in ordinary damped recovery and in the terminal certificate path.

This was rejected on measured behavior, not theory alone:

| Specimen | Baseline | Diagonal-anchor candidate |
|---|---|---|
| W5a, 60 kPa / 85 °C / 22.5% | reached 90 kPa at 2.52e-8 material residual | failed earlier at 100 kPa, 2.03e-5 energy residual |
| W5b, 60 kPa / 90 °C / 22.5% | 2.60e-9 material residual at 110 kPa | 1.42e-5 energy residual at 110 kPa |
| W5c, 60 kPa / 95 °C / 22.5% | failure | effectively unchanged |

At the initial damping, ordinary LM contributes only `1e-8 * max(1, H_ii)`. The candidate
therefore made selected coordinates about eight orders of magnitude stiffer. It also restricted
the VLE-ratio direction along with the undesirable common direction.

## Mechanism to preserve

W10's direct autopsy observed equal-sign liquid/vapor log-flow corrections for trace pairs, for
example paired corrections of roughly 0.007–0.02 while other corrections were at or below 1e-5.
The ill-determined mode is therefore approximately

```text
q = (e_liquid + e_vapor) / sqrt(2)
```

not either coordinate independently. The physically useful differential/VLE-ratio mode
`(e_liquid - e_vapor) / sqrt(2)` must remain free.

## Safe candidate design — not yet activated

If a current-branch autopsy establishes a residual-gated trace-pair specimen, the first W-3
experiment should be terminal-only and use a rank-one common-mode prior per qualifying pair:

```text
H_conditioned = H + rho_p * q_p * q_p^T
rho_p = alpha * max(1, (H_LL + H_VV) / 2)
```

A pair qualifies only when both local phase fractions are below the trace floor **and** the actual
Jacobian confirms that its common mode is near-null:

```text
||J q_p||_2 <= epsilon * max(||J e_L||_2, ||J e_V||_2)
```

The rank-one contribution adds `rho/2` to LL, VV, LV, and VL. It is stage-local, so it remains
inside the existing banded linear algebra. It must be applied only after the unmodified terminal
correction fails, never as a broad substitute for every ordinary recovery direction.

## Certificate guardrail

A solution of a regularized normal system is not a final Newton correction for the original
`J * delta = -r` system. Its backward error is for a different matrix. Therefore:

1. Do not relax `V3ConvergenceEvidence` to accept residual plus audit alone.
2. Do not label the conditioned correction as a final Newton step.
3. If a conditioned step finds a better coordinate, recompute a fresh **original** FD Jacobian
   there and require the existing unregularized final Newton certificate before publication.
4. Retain the fresh physical residual gate (`<= 1e-8`) and every independent acceptance-audit
   check. The audit's residual families use a fresh evaluator but are not a separate MESH oracle.

## W-3 terminal-domain rescue experiment — rejected

Passive certificate instrumentation (`8cde759`) established a more specific W5b texture than
the earlier aggregate counters showed. At the 110 kPa pressure leg of the 60 kPa / 90 C / 22.5%
case, the residual reaches `2.5982087724832363e-9` (below the `1e-8` gate), but each of 20 final
certificate entries forms a raw direct correction whose decoded log-flow candidate is outside the
finite-positive-flow domain. It is a certificate-candidate domain failure, not simply an exhausted
Newton budget.

One bounded, temporary rescue was implemented and benchmarked. It was eligible only after that
exact direct-domain texture, took one unanchored damped-normal Armijo search state, discarded its
regularized backward error, rebuilt a fresh original FD Jacobian, and required a fresh direct-only
certificate before it could publish convergence. The fresh direct correction failed the same
domain check. The benchmark therefore remained `NONCONVERGENCE` at the same residual and failed
pressure leg:

| Run | Outcome | Residual | Wall time | Terminal rescue result |
|---|---|---:|---:|---|
| Baseline | F / `NONCONVERGENCE` | `2.5982087724832363e-9` | 57.07 s | no rescue |
| Temporary rescue | F / `NONCONVERGENCE` | `2.5982087724832363e-9` | 60.72 s | one factorized normal direction and one residual-gated search state; fresh direct certificate still domain-invalid |

Because it gained no audited success and added cost, the rescue was completely removed. The
rollback was validated with the focused solver, normal-equation, and DWSIM continuation tests.
The full evidence is recorded in `V3_W3_TERMINAL_RESCUE_BENCHMARK.md` and the two JSON reports
under `build/reports/benchmarks/`.

This does **not** justify accepting a regularized correction as a certificate or enabling a broad
trace-pair conditioner. The next W-2/W-3 implementation candidate must first be supported by a
test-only local Jacobian autopsy that proves the qualified common mode `q` is actually near-null
at the residual-gated state and that a conditioned search can subsequently obtain a fresh,
unregularized certificate.

## Required evidence before implementation

- Add bounded `FINAL_CERTIFICATE` telemetry distinct from pivot/conditioning recovery.
- Reproduce a current-branch residual-gated W2/W5 state and perform a test-only state/Jacobian
  autopsy (phase fractions, `Jq` norms, corrected directions).
- Unit-test the rank-one matrix contribution, anti-mode preservation, empty-plan bit identity,
  one-phase/non-null exclusion, and cancellation.
- Prove a conditioned state can subsequently pass a fresh unregularized final certificate.
- Run W5a/W5b/W5c, W1/W1c, and the cold DOE against baseline. Retain only an audited-success
  gain; otherwise roll back.

## W5b residual-gated Jacobian autopsy — qualified for a narrow experiment

The property-gated `V3W2W3NullDirectionProbeTest` now invokes the current production pressure
continuation driver and captures its actual W5b terminal state rather than approximating the
adaptive draw ramp. It reproduced the expected 110 kPa recovery endpoint exactly:

```text
24 recovery iterations
maximum scaled residual = 2.5982087724832363e-9
fresh terminal-certificate replay = one DIRECT_CANDIDATE log-flow-domain failure
```

At a diagnostic phase-fraction floor of `1e-3`, 98 two-phase trace pairs were found. The fresh
991-row FD Jacobian confirms the relevant geometry: common-mode `Jq` is near zero while the
differential/VLE-ratio direction remains well determined. For example, PC12 on node 22 has
`x_L = 1.441e-7`, `y_V = 1.667e-12`, `||Jq|| / max(||JeL||,||JeV||) = 9.561e-7`, and the
differential ratio is `1.414`. PC12 at nodes 19–21 is even more degenerate (common-mode ratios
between `3.754e-19` and `6.370e-9`) while retaining the same `1.414` differential ratio.

This meets the evidence bar for one **terminal-only, rank-one common-mode** conditioner
experiment. It does not authorize the rejected diagonal anchor or a broad ordinary-recovery
change. The candidate must remain a temporary search direction and must still gain a fresh raw
certificate and independent audit; W5b must flip to audited success before it can be retained.

## Rank-one terminal experiment — rejected and removed

The narrowly qualified candidate was off by default, ran only after the exact raw
`DIRECT_CANDIDATE` log-flow-domain failure, added only `rho*q*q^T` common-mode curvature,
required a residual-gated Armijo state, and then required a fresh raw direct certificate. It was
screened on W5b across alpha values from `1e-8` to `1e-1`.

Every run took all 20 eligible searches, qualified 1,460 trace pairs in aggregate, factorized all
20 conditioned systems, and found 20 residual-gated temporary states. **None** produced a fresh
raw certificate or an audited success; each remained `NONCONVERGENCE` at 110 kPa with the exact
same `2.5982087724832363e-9` residual and 24 recovery iterations. Wall time ranged from 61.11 s
to 64.93 s, so it also did not improve work before failure.

The candidate was fully removed without a commit. A post-rollback W5b run restored the baseline
outcome, residual, iteration count, path, and event sequence (59.61 s, ordinary timing variation).
The reusable result is diagnostic only: the null direction is real, but a terminal common-mode
search cannot make its next raw certificate representable. See `V3_W2_W3_RANK_ONE_BENCHMARK.md`.

## W5b dry-tray re-resolve — not eligible

The current exact W5b terminal state was independently checked before treating its trace-pair
collapse as a dry-tray event. The property-gated test replays the production 110 kPa Wang-Henke
recovery (24 iterations, `2.5982087724832363e-9` scaled residual, identity trace support), calls
`V3DryTrayTransition.assess`, and would re-solve/audit its prepared vapor-only topology only when
the structural threshold is crossed.

It returned `UNCHANGED`. The `1e-6 F` floor is `7.251944444444443e-4 mol/s`; the thinnest whole
tray is 23, below the deepest draw, at `3.9031809783484093 mol/s` (`0.0053822544 F`), roughly
5,382 times above that floor. There is therefore no valid vapor-only projection to test at this
W5b endpoint. This cleanly rules out a whole-tray dry-topology transition as the cure for this
specific certificate failure; W-1 remains relevant to the truly collapsed draw-attach walls,
while W5b remains a trace-pair/certificate problem. The durable structured evidence is
`build/reports/benchmarks/v3-w5b-dry-tray-reresolve.json`.

## Raw direct-correction offender autopsy

The exact W5b state is independently **audit-accepted** even though it cannot publish without
final Newton evidence. Every current audit check passes: finite topology, side-draw split
(`0.5060963`), local material (`2.5982087724832363e-9`), equilibrium (`8.3977e-13`), energy
(`1.3697e-11`), and condenser phase (`5.6838e-13`). This preserves, rather than relaxes, the
current material admission policy.

Reconstructing the raw direct correction before coordinate decoding finds exactly two domain
offenders: liquid and vapor PC11 at node 1. Both carry the same `+5.77920292050021e8` log-flow
correction. Their common-mode correction is `8.173027149877597e8` and their anti/VLE-ratio
correction is exactly zero. The raw solve's backward error is `5.2077e-20`; its problem is an
unbounded common null direction, not a failed direct factorization. This qualifies one last
strictly test-only candidate: constrain only this offender-local common direction for a temporary
search, then require the existing fresh raw certificate and audit before publication. Full evidence:
`V3_W2_W3_DIRECT_OFFENDER_AUTOPSY.md`.

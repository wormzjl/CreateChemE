# Column GUI evaluation against ColumnSim

Date: 2026-09-24. **Concluded 2026-09-24 — evaluation only; implementation not started.**

## Recommendation

Make a selectable column diagram the primary way to inspect a solved case. Clicking a tray, condenser, sump or product should select an inspector, with profiles, stream tables and duties alongside it. Group editing into Setup and keep Solve, request state, result freshness and important warnings visible across pages.

Reuse ColumnSim's organization and scientific visibility while retaining Minecraft widgets, current material packages, diameter/hydraulics controls, server authority and engine-owned presentation. This is a moderate GUI project with a significant result-publication prerequisite, not just a skin change. No numerical algorithm replacement is needed for profiles and inspection.

## Evidence and limits

- Main source inspected at `f9d6be10de8f73a0a56ece3effe2cd572803b485`.
- MATLAB reference: `D:/Minecraft/Modding/1.21/CreateChemE-matlab-v3-tool/matlab/columnsim.m`, its README and `build/matlab-gui-stage.png`. This implementation derives from Java `ca5bdb6`; its old cases, components and prescribed pressure-drop behavior are not specifications for current Java physics.
- Minecraft visual evidence: `documentation/2026-09-06-v3-literature-cdu-pumparounds/screenshots/after-heat-result.png` and that batch's `V3_PUMPAROUND_GUI_REVIEW.md`. These are historical screenshots, not current-build captures. Current source includes later changes, including four cooler rows and hydraulics.
- Bridge status: `connected=false`, `processAlive=false`; `.mcp.json` absent here. No client was launched, no Gradle invoked, no product sources edited. Findings are from source and saved screenshots, not newly reproduced in-game defects.
- Implementation must be verified through langyo/minecraft-mod-mcp in a fresh dev world. No old-world testing or migration is proposed.

## Comparison

| Capability | Minecraft now | ColumnSim reference | Recommendation |
|---|---|---|---|
| Diagram | Noninteractive ladder on Heat, hidden below 560 logical pixels | Central selectable column, equipment and connections | Promote to Overview; select equipment to inspect or navigate to its inputs |
| Profiles | None | Temperature, pressure, traffic, component x/y | Add accepted profiles; temperature/pressure first |
| Stage inspection | None | T, P, HC liquid/vapor, water vapor, free water, x/y | Principal detailed result view |
| Products | One to three stream panels per page | Compact stream summary | All-products overview plus selected-stream composition; retain mol%/wt% |
| Feed | Total flow rescales server composition | Editable component flows | Read-only composition first; custom-feed editing later |
| Connections | Draws/steam on Inputs, coolers on Heat | Grouped editable tables | Group Side draws / Steam / Coolers, with explicit row enable/remove behavior |
| Evidence | Audit count, residual, iterations, provenance, hydraulic summary | Individual audit values/limits and advisories | Actual check details and all warnings |
| Freshness | Heat has edited flag; Streams uses server status | Edits clear results | Global freshness; previous accepted result available only as explicitly historical |
| Progress/cancel | Calculating label; no screen Cancel action | Route, iteration, residual, cooperative cancel | Engine-delivered state first; detailed telemetry/cancel separate scope |
| Cases/export | Server presets | JSON case, MAT/CSV export | Later client-local case and CSV export with current schema validation |

The key improvement is answering “What is happening on this tray, and how does it change above or below the feed?” Coloring the existing ladder alone would not achieve it.

## Findings that precede charts

### Result freshness is inconsistent

`ColumnCalculatorV3Screen.onDraftEdited` sets `draftEditedSinceState`, but `renderStreams` (lines 708–725) calls a result current whenever the server status is SUCCESS. Local edits do not alter that status. `validateDraft` can also still say calculation complete after a valid edit. The Heat page recognizes local editing, so pages can disagree.

Maintain local draft, acknowledged input, submitted request identity and accepted-result identity separately. Show “Inputs changed — showing previous accepted result” globally. Clear result colors from the draft diagram or explicitly switch to a previous-result view with its own solved topology. Never paint a changed tray count/feed location with an old case's values.

Do not compare resultRevision numerically with inputRevision: they are independent counters. Bind a result to its solved input revision/digest and scientific revisions. Preserve untouched precise values behind formatted fields so merely displaying rounded input does not create a false mismatch.

### Server refresh can overwrite unsent edits

`applyServerState` (348–357) calls `loadInput` unconditionally and clears the dirty flag; equal state revisions are accepted. Another viewer, catalog refresh or duplicate reply can replace a draft. `init` also saves drafts then reloads server input, making scalar/side-draw/steam edit preservation on resize suspect. These paths need live reproduction in the implementation batch.

Preserve dirty fields, focus, selection and scroll on refresh/resize. Match responses to requests: the rejection callback currently ignores nonce and reason. A remote input change should offer “Use server input” or “Keep my draft”; retained drafts must be revalidated against the latest acknowledged base before resubmission. Status-only refreshes must not erase typing.

### Column transport still violates the standing presentation rule

`ColumnV3Network.handleCalculate` starts/submits an operation and immediately calls reply/pushToViewers (129–166). State requests reply immediately (169–180); presets likewise (183–210). Completion and catalog-refresh paths also push. `ColumnCalculatorV3Menu` exposes status via a live DataSlot, which must be covered by a presentation redesign.

Queue actions as engine events, drain completions into immutable server state, and publish acknowledgements/status/results only at the engine's presentation deadlines, roughly 100 online ticks. Menu open registers interest for the next bucket. Cover multiple viewers, duplicates, unload and stale completions.

A button may immediately show the local fact “Request sent — awaiting engine” and prevent duplicate submission. It must not imply server acknowledgement. Selection, tooltips and plotting already-received arrays remain immediate local UI operations. No thermodynamics on client rendering or Minecraft ticks; no pretend progress between snapshots. Diagnostic solve duration must never advance simulated time.

### Layout shrinks without enough reflow

The panel is capped at 620×360 logical pixels; available width/height shrink it, but many text/editor/footer coordinates remain fixed. The diagram has a width cutoff; the remainder lacks comparable reflow. Small windows and large GUI scales therefore have clipping/overlap risk from source inspection.

Use scrollable bounded content and a reserved footer. Work in logical GUI pixels. Do not shrink MATLAB's 1500×900 three-pane interface until its text becomes unreadable.

## Proposed layout

Four top-level destinations: **Overview / Setup / Results / Diagnostics**, replacing the existing organization.

```text
Column calculator   [Case]    [Current / Edited / Previous result]
Overview | Setup | Results | Diagnostics
+------------------+--------------------------------------------+
| Selectable       | Tray / stream / cooler inspector           |
| column diagram   | Temperature, pressure, phase flows         |
|                  | [Details] [Profile] [Composition]           |
| Condenser        |                                            |
| Trays, feeds,    | Contextual plot or table                   |
| draws, coolers   |                                            |
| Sump and steam   |                                            |
+------------------+--------------------------------------------+
[Solve]  Request/result state                      [Warnings: n]
```

Conceptual sketch using the show-me skill; not a verified pixel mockup. At the existing maximum use two panes, roughly one third for the diagram. Setup gets full-width Operating / Feed / Connections groups. Results contains Profiles / Streams / Heat. Diagnostics starts with readable failures/warnings, then audit and collapsible provenance.

Narrow mode switches between diagram and inspector while preserving selection, rather than hiding the only tray selector. Wide mode can optionally add an input pane. Do not require wide mode for basic operation.

### Interaction and scientific details

- Physical nodes: condenser 0, trays 1…N, sump/reboiler N+1; distinguish terminals from equilibrium trays.
- At 64 trays, rows are too small for reliable clicking in the current height. Add stage number, previous/next and keyboard selection; zoom/scroll is supplementary. Resolve overlapping connection markers with a selection list.
- Use a sequential temperature scale with numeric legend. Arrow direction and line style distinguish streams independently of color. Keep external sump steam separate from internal boilup.
- Inspector separates hydrocarbon L/V, water vapor and free liquid water. Label x/y as hydrocarbon compositions. Absent phases have zero flow and N/A composition, never invented normalized fractions.
- Coolers remain prescribed stage heat, without invented circulation flow or exchanger outlet temperature. Retain positive Cooling removed (MW) inputs and explicitly signed result duties rather than copying MATLAB's signed kW editor.
- Plot actual accepted pressure for diameter-driven hydraulics, never nominal input drop. Retain diameter, total drop, worst flood fraction/tray and actionable flood warnings. Per-tray flood curves would require additional data beyond the current summary.
- A product selects actual product-stream properties; a tray selects tray phases. Do not substitute one for the other.
- Group Operating into feed conditions, geometry/pressure mode, condenser/reflux/reboiler. Label prescribed-drop and calculated-from-diameter modes explicitly over the existing contract.
- Field-level errors plus an off-page error summary; retain pure draft parsers. Keep localized materials and precise trace values available in details/tooltips/export.
- First expose feed composition read-only. Later component-flow editing must retain registered basis/order and server validation; normalization must be explicit.
- Diagnostics display actual audit value/limit and metric normalization/units. Do not rename every metric percent closure or treat a small Newton residual as acceptance. Failed attempts may have no audit; say unavailable. Separate previous accepted metrics from latest failure. No fabricated percent-complete bar.

## Data and architecture prerequisite

`V3ColumnDisplayResult` explicitly excludes profiles: it carries streams, duties, count of checks, final residual/iterations, closure tolerance and hydraulic summary. `V3ColumnResult` retains full audit evidence but not the accepted temperature/component-flow profile. `V3DryMeshState` is internal and explicitly must not cross the public boundary.

Create a bounded immutable presentation snapshot at `V3ColumnResult.accepted(...)`, where accepted state is still available. Extract on the worker publication path after the acceptance gate; this is not a second solve. Include:

- Solved input/result identity, dataset/formulation identity, stage count, ordered component IDs, and solved topology sufficient to label retained results.
- Node temperatures and actual pressures, HC L/V totals, water-vapor/free-water flows with explicit terminal semantics.
- Liquid/vapor composition arrays and phase-present flags, with explicit units/basis.
- Bounded audit checks and advisories, alongside existing stream/duty/hydraulic data.

Raw numeric size estimate: `8 × (N+2) × (6+2C)` bytes for six scalar columns plus two compositions. At 40 trays/20 components, 15,456 bytes (~15.1 KiB); at the current 64-tray/64-component limits, 70,752 bytes (~69.1 KiB). These are estimates, not measured packets or heap. IDs, masks, topology, audit strings, existing streams and codec overhead are additional. Check real payload limits before choosing one payload versus bounded chunks.

Send changed snapshots on the engine bucket and once to new viewers at their next bucket. Cache by result identity; unchanged status updates need not resend profiles. Selection uses local cached data, not one request per hover. Bound caches and release transient viewer state.

Persist the bounded accepted snapshot if inspection is to survive save/reload, matching current compact-result persistence. This touches accepted publication, network codecs and block-entity persistence. Validate dimensions, ranges, finite data, component order and identities on read. New-format fresh-world round trips must preserve relevant engine clocks, pending events and presentation state. No migrations, absent-field normalization or legacy-save gates.

The numerical API already permits cooperative cancellation, but a player Cancel action additionally needs operation identity, authorization, queued routing, terminal-state handling and late-completion rejection. Hiding a screen is not cancellation. Detailed iteration history similarly needs bounded worker telemetry; final iterations/residual alone are not progress history.

## Suggested delivery order

| Order | Deliverable | Relative effort |
|---|---|---|
| 1 | Shared draft/freshness state, preserve edits on refresh/resize, useful rejection messages, persistent status/actions, scrollable layout | Medium |
| 2 | Engine-owned column actions/presentation, including menu status, acknowledgements and completion publication | Medium–high; prerequisite for richer delivery |
| 3 | Accepted snapshot, wire/persistence, audits, selectable Overview, inspector and profiles | Medium–high; principal MATLAB-like improvement |
| 4 | Stream overview, connection editing, units/help/localization refinement | Medium; reuses existing streams/parsers/material names |
| Later | Case/CSV export, custom feed, cancellation, detailed progress, comparison of saved cases | Separate scope |

Items 1–3 form the first meaningful redesign. Draft ownership, result identity and delivery are harder than drawing bounded plots. These are scope estimates, not elapsed-time commitments. Keep current solver policy and tolerances; do not copy old MATLAB defaults, raw component IDs or its numerical implementation.

## Implementation verification

1. State: edit after success, invalid edits, failed rerun retaining results, duplicates/out-of-order snapshots, two viewers, preset switch, resize, catalog changes. No lost draft and no mislabeled result.
2. Data: compare extracted snapshot to accepted worker state for dry, steam/free-water and hydraulics cases. Test absent phases, 2/64 trays, 1/64 components, malformed dimensions/nonfinite values, new wire/persistence round trips.
3. Engine: no immediate packet/menu acknowledgements, all state on buckets, no repeated unchanged profile transmission, no process calculations on ticks/rendering.
4. GUI: fresh dev world via MCP bridge, narrow/standard/wide logical layouts, actual field typing, 40/64-tray navigation, every product, four coolers, overflowing diagnostics, keyboard selection, save/reopen. Record screenshots and bridge limitations.
5. Run applicable existing gates without changing tolerances or narrowing suites. One Gradle invocation, no suite during a dev client. No solver campaign is needed for this evaluation.

No product changes, tests, probes or instrumentation were created in this task. There is no detached tool material to index. Documentation only.

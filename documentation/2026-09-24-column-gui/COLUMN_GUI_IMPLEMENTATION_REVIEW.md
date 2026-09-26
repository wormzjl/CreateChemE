> Follow-up: COLUMN_GUI_INTERACTIVE_REVIEW.md supersedes this first iteration's screen organization and retained-result behavior.

# Column GUI implementation review

Date: 2026-09-24. **In progress since 2026-09-24 — implemented on codex/column-gui, not merged.**
Base: f9d6be10de8f73a0a56ece3effe2cd572803b485.
Implementation commit: cec64e4aebf81fd4d899dda5e337fbb26ff75112. Changelog commit: dfcca51.
Worktree: C:/Users/wormz/.codex/worktrees/column-gui/CreateChemE.

## Delivered behavior

- Four destinations: Overview, Setup, Results, Diagnostics. Native Minecraft controls, persistent Solve/status, width-dependent diagram/inspector layout, scrolling, full-text tooltips and localized material names.
- Overview draws the accepted topology with a temperature scale and selectable trays/products. A stage-number field and previous/next buttons support 64 trays. Narrow layout switches diagram/inspector; wide layout shows both.
- Results plots accepted temperature, actual pressure, hydrocarbon liquid/vapor traffic, water vapor/free water, and selected hydrocarbon liquid/vapor compositions. A plot click selects a physical node. Product rows and diagram markers open actual product composition, including mol% and wt%.
- Setup groups operating values, read-only feed composition, connections and server cases. Wide operating forms use two columns; connection forms scroll through supported rows. Positive cooling-MW inputs remain.
- Diagnostics shows bounded advisories, audit values/limits, final residual/iterations, hydraulic warnings and provenance. Long evidence wraps.
- One freshness policy covers all pages. Edited, pending, failed and reloaded states show previous accepted results using their original solved topology. Saved results remain presentation-only until recalculated.
- Drafts live outside widgets. Tab switches and reconstruction retain them. Untouched physical values remain exact; changing tray count remaps sump steam without rounding its flow/temperature.
- Ordinary refreshes preserve dirty drafts. Changed remote input offers use-server/keep-draft choices. Acknowledgements/rejections are correlated by nonce; older state revisions are ignored.

## Data and transport

V3ColumnInspection is immutable: solved input, node T/P, hydrocarbon L/V totals, water-vapor/free-water flows, hydrocarbon x/y and accepted audit. Node 0 is the condenser, nodes 1..N trays, N+1 sump. Condenser free water means the decanted product; tray free water means downward aqueous flow. Absent phases have zero flow/fractions and render N/A compositions.

Extraction runs at accepted scientific publication while internal V3DryMeshState is available. Exact-zero numerical elimination is expanded onto the public component axis. The profile never exposes workspaces or acts as a warm start. Convergence tolerances and numerical policy are unchanged.

V3InspectionCodec supplies one binary representation for wire/NBT, capped at 262,144 bytes. It validates lengths, dimensions, finite/range constraints, composition closure and accepted audit; trailing/truncated payloads fail. Maximum 64-tray/64-component profiles round-trip in tests. The installed Minecraft 1.21.1 source confirms a 1,048,576-byte clientbound custom-payload limit.

**Breaking development format:** wire schema 13 / protocol 9, block data version 11. New saves require explicit input lists, diameter, closure and inspection payload. No migration or absent-field normalization was added. Older migration assertions were replaced by current-format missing-field rejection assertions, retaining the test cases. Existing source constructors support legitimate summary-only scientific callers; they are not old-format decoders.

## Engine ownership and lifecycle

PresentationMailbox is server-thread confined. It uses the existing FluidWorldAuthority online epoch and a 100-tick deadline, with an O(1) due check between buckets. Each menu has at most one pending command. At a bucket, valid commands apply before any viewer publishes.

ColumnPresentation routes calculate/preset inputs, acknowledgements, rejections and state through those buckets. Packet handlers/menu opening do not publish immediately. The live menu DataSlot was removed. Worker completion commits server state; a later bucket publishes it. Unchanged state does not resend profiles; new viewers/catalog refresh receive scheduled views.

Session identity includes player, menu, dimension and block-entity instance. Closing/replacing the menu, replacing the block or changing dimensions expires an unsent command. These are transient UI submissions, not durable process events; disconnect/shutdown ends their session. Once admitted, operations use the existing bounded service and shutdown rules. There is no new executor or wall-clock simulation. The authoritative persisted online epoch remains in the existing fluid checkpoint; inspection has no clock or optimization state of its own.

## Verification

Final ordinary suite: **1049 tests, 0 failures, 0 errors, 0 skipped** (final source). Recorded command: gradlew.bat test --offline --console=plain, JDK 21.0.11; log in final-tests.log.
An earlier full run passed 1,048 tests. Focused checks passed after GUI fixes and the additional exact-steam regression. Added coverage:
- Deadline delivery, all inputs before views, duplicate suppression, closed sessions and restored-epoch scheduling.
- Exact untouched input identity, independent-field edits, invalid drafts and exact sump-steam remapping.
- Minimum/maximum dimensions, immutable nested lists, invalid values, truncated/trailing/oversized payloads, real wire/NBT round trips.
- Accepted extraction, zero-feed public component positions, terminal temperatures and pressure profiles.
- Existing free-water tests rerun after using the physical wet-tray accessor.

The first full run exposed the active/public component-axis mismatch in extraction; it was fixed and covered. No solver tolerance was changed to make a test pass.

### Dev-client evidence

Used the existing runMcpClient task, langyo/minecraft-mod-mcp 0.3.0 bridge (SHA-256 c6cc12c960490e72a83fd5c2f8169294cd1adda4d838ad69d188e8b7d3b73695), and existing input compatibility source set. Local .mcp.json and jars are in this worktree; .mcp.json is now ignored. MCP tools and the same bridge's documented local HTTP API drove the UI. Existing tools/mcp-gui-helpers/Flatten fixed bridge PNG alpha for inspection.

Created **Column GUI verification**, a new superflat world in this task's run/mcp-client/saves. Reopens used this new version-11 save, never an old-format world. No suite/campaign overlapped the client.

Observed:
- 40-tray CDU completed with SUCCESS and published accepted inspection.
- Real keyboard input changed feed temperature; Results labelled the plotted snapshot previous.
- 64-tray case completed with SUCCESS through the actual service: feed tray 37, 1,200 kmol/h sump steam, diameter 8 m. Diagnostics showed 37.290 kPa total drop and worst-tray flood fraction about 70.186%, rather than the nominal zero-drop input.
- Direct node entry selected tray 64. Corrected diagram clicking selected tray 33; corrected profile clicking selected tray 43; Draw 18 opened its actual stream composition.
- 854x480 framebuffer at GUI scales 1 and 2 exercised wide/narrow layouts. Narrow overhead labels group outlets and remain separate; Inspect opens details.
- Accepted profiles/topology/audit survived save/reload and were marked previous/presentation-only.
- Clients exited through save-and-quit and normal application exit. Final log had no column runtime fault.

Visual testing found and fixed crowded product labels and AbstractContainerScreen consuming custom clicks before the plot/diagram handlers. Final interaction screenshots were captured after correction.

| Screenshot | Verified content |
|---|---|
| final-setup.png | Two-column operating form, all ten values |
| final-narrow.png | Narrow 64-tray topology and separate labels |
| 64-tray-result.png | Live accepted 64-tray result |
| final-tray64.png | Direct selection of tray 64 |
| final-profile.png | 64-tray temperature profile and axes |
| final-diagnostics.png | Audit/advisories and computed hydraulics |
| edited-profile.png | Real edit marks accepted data previous |
| reopened-result.png | Saved version-11 inspection survives reopening |
| verified-stream-click.png | Corrected product click opens Draw 18 |
| verified-profile-click.png | Corrected profile click selects tray 43 |
| verified-tray-click.png | Corrected diagram click selects tray 33 |

The two-client conflict flow was source-reviewed, not exercised with two Minecraft clients. Physical window resizing with a dirty focused field was not separately driven; tab navigation and typing were exercised, and the draft is independent of widget lifetime. These are verification limits, not claims of live coverage.

## Scope and repository hygiene

Case export/import, custom feed editing, iteration-history telemetry and user cancellation remain the deferred follow-ups from the evaluation. No screenshots/scientific values were fabricated.

The owner also requested removal of mandatory commit attribution; AGENTS.md removes that line.

No temporary probe, instrumentation patch, campaign script, new Gradle switch or test-only product code was introduced. New tests run in the ordinary suite. Existing local MCP helpers were reused; no newly detached tool folder needs indexing. Local launch configuration, screenshots and logs remain ignored. Main is unmerged; mod_version stays unchanged until merge, per policy.

Dev-client launch follow-up (2026-09-24): owner requested an interactive client; launched runMcpClient from codex/column-gui after checking no client/test invocation was active. Client left running for the owner; no source changes.


# Requested opening snapshot review

In progress since 2026-09-24 on codex/column-gui; implemented at fa12037, not merged.

## Owner rule and behavior

AGENTS.md now specifies request-based GUI/process delivery. Opening a GUI requests the last published snapshot and subscribes to scheduled live updates; closing ends the subscription. No background GUI contents are broadcast to clients without an active request. Replaying cached data must not calculate, materialise or advance process state, or apply pending inputs. New state and input acknowledgements remain engine scheduled.

Fluid equipment now stores complete MenuData snapshots on the server during its normal presentation buckets, even when no GUI is open. Each snapshot includes its view, controls, component axis, names, presets and molecular weights from the same registration revision. Player-specific replies are never stored in this shared cache.

After ServerPlayer.openMenu installs the requested menu and sends its opening packet, the block sends cached static/live payloads through the existing protocol. The subscription remembers the replayed static revision, avoiding another unchanged metadata payload on the next bucket. Replay does not change update deadlines or consume pending acknowledgements. Network delivery verifies menu/player/device identity and menu validity.

Cache entries are released on unload, device removal and server close. If a device has no published snapshot yet, the GUI waits for its first scheduled publication. This implementation covers fluid-device GUIs (generator, reservoir, pipe, pump, valve, sink and filter); the column calculator's separate presentation path is unchanged in this follow-up.

## Verification

- 21 presentation-bucket and fluid-packet regression tests passed, zero failures/errors/skips; BUILD SUCCESSFUL in 5s.
- The existing real-server opening GameTest was updated for immediate cached replay and unchanged later deadlines, and compileFluidGameTestJava passed. The complete GameTest server suite was not run in this follow-up.
- New tests verify first opening after background operation, exact reuse of the older published view, no extra island reads/view builds/solver runs, unchanged next deadline, live-only next bucket when metadata is unchanged, preservation of pending replies, stale published revisions followed by fresh metadata, no-cache startup and refusal to replay without an active request.
- Live MCP verification used a newly launched client session in Shared process GUI; no GUI had supplied a client-side remembered view for the tested tank.
- On first opening, a screenshot captured 597 ms after issuing right-click already showed Nitrogen, 25.0 C, 101.3 kPa, 1000.0 L and 1.1 kg. Its cached presentation timestamp was 3620.6 s.
- A later screenshot showed timestamp 3665.6 s, confirming scheduled updates continued after the older opening snapshot. The first screenshot did not wait for a fresh snapshot.
- No save or wire format change; reused the same-format verification world. No tests overlapped the dev client or another Gradle invocation. git diff --check passed.
- Client remains open on the tank GUI for owner testing.

Evidence: opening-snapshot-test-summary.txt, screenshots/opening-tank-first.png and screenshots/opening-tank-next-update.png.

## Cleanup

No one-off source instrumentation, runtime switches or detached harnesses were introduced. Added checks are part of the existing presentation test gate, and the revised GameTest remains in its documented server test source set.

# Pre-implementation cold baseline

Captured 2026-08-31 before phase-transition/performance implementation. Each JSON contains exactly one first solve in a fresh JDK 21.0.11 JVM, `--warmup=0 --samples=1 --deadlineSeconds=45`, 512 MiB initial / 2 GiB maximum heap. Runs were serial with no Minecraft client. Input construction and JVM startup are outside the solver timer; no previous solve or JIT warmup precedes it. Existing idle Gradle processes were left untouched.

Order: `150-off,110-off,110-on`; reversed order for repeat 2; original order for repeat 3; then `100-off,110-test-feed`. Each invocation used the existing `v3TimeoutBenchmark` Gradle JavaExec task with `--offline --no-daemon --console=plain`, one case, and a unique report path in this directory. File suffixes identify rounds, not a shared-JVM sample sequence.

Results: 150 kPa controls all succeeded (14.16–14.91 s); 110 kPa off/on all reached the 45 s deadline (3 fresh JVMs each); 100 kPa returned the pre-existing flash-nonconvergence property failure (19.39 s); 110 kPa at 2000 kmol/h succeeded (25.67 s). No acceptance tolerance, production deadline, or input configuration was changed.

Git base: `6185117078585b0523c6016a42ef731c9687ab1a`, branch `codex/stage-trace-truncation`, including the pre-existing uncommitted stage-trace implementation. These SHA-256 values were read before the first case and checked unchanged after the final case:

| Production source | SHA-256 |
| --- | --- |
| `V3ColumnCalculator.java` | `50E967FEE73C790CD3A0EEAD9B55C552184CA2FFD01A2C97AFB65837D5BCF814` |
| `V3SimultaneousColumnSolver.java` | `FB6D0D0DEC3D08FF98DDC2A9573C75AD1DF4DC5BDC487AF60D03AD56C62F1E43` |
| `linalg/V3BandedPivotedSolver.java` | `8C38BE2C8C1CC58516DD45E9732D50343540307D3C83B8D72AA98BF0A8F21B9B` |
| `V3AcceptanceAuditor.java` | `F2F8D0DA1D52932E8920C42E0B44E693565265E0855C8EC90AA074500B30CADF` |

Paths above are relative to `src/main/java/com/wormzjl/createcheme/science/column/v3`. This baseline predates all implementation edits for the warm phase transition and measured linear-algebra fixes.

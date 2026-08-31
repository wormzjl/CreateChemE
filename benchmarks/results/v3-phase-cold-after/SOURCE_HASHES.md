# Post-change production source fingerprint

These sources were unchanged throughout the lower-pressure and paired cold after-runs. Paths are relative to `src/main/java/com/wormzjl/createcheme/science/column/v3`.

| Source | SHA-256 |
| --- | --- |
| `V3ColumnCalculator.java` | `4D69B903F27972F67ACA94BAD485209AEB9758347AE94D31740A58851C4B4499` |
| `V3CondenserPhaseTransition.java` | `06191199DFB0E8B162E5B6E35D99FCA04714F2EEB6B7DE14EA8E1A50C3BC45FF` |
| `V3NormalEquations.java` | `79A32543AF31B9348D8EB54C91C0BAB994A7FCB989C564971A3DA93713FCD96D` |
| `V3SimultaneousColumnSolver.java` | `A9972DF2FB68B1915D11B2498F1A15681DF1845FDD9D132CC518806E41911333` |
| `linalg/V3BandedPivotedSolver.java` | `FCF75A272ED59D0BB1134C5B703F6336679AF10E80F570DDCFEFC99DC1CDA08C` |
| `V3AcceptanceAuditor.java` | `F2F3898B2925E95B65721C9EE011E8F8B556A74FAFBC8FC0A8FDD53FC551E176` |

This is the after-matrix of eleven independent JVMs, not eleven solves sharing a warmed runtime. The benchmark definition and numerical inputs match the before matrix; additional 50/70/100 kPa observations are stored separately under `v3-phase-low-pressure`.

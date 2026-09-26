# Pipe thermal-loss experiments

Purpose: offline numerical comparison for batch `documentation/2026-09-24-pipe-thermal-loss/`. Canonical local tool; not part of game or gate-suite discovery.

Base: `f9d6be10de8f73a0a56ece3effe2cd572803b485`.
Experiment branch: `codex/pipe-thermal-loss-study`.
Worktree: `C:/Users/wormz/.codex/worktrees/pipe-thermal-loss-study/CreateChemE`.

## Contents

- `prototype.patch`: exact addition of the candidate `PipeHeatExchange` kernel and its six gate tests against the base. Changes remain uncommitted on the experiment branch. Apply to a fresh worktree with `git apply <absolute-tool-path>/prototype.patch`; do not apply again to the current experiment worktree.
- `java/.../PipeThermalStudy.java`: main 600-case campaign, eight workers; also serial repeated timings after pool shutdown.
- `java/.../PipeThermalFollowup.java`: fixed-size thermal tables and independently checked references, eight workers.
- `study.init.gradle`: external study source set, compile task and Java/classpath export. No tracked build switches.
- `analyze.ps1`: reconstructs `research/2026-09-24-pipe-thermal-loss/summary.json` from saved CSVs.

No code was removed from a tracked path. The harness was authored directly here, so there is no removal commit or reattachment patch for the harness. `prototype.patch` recreates the unmerged candidate, not deleted production instrumentation.

## Reproduce

Use PowerShell in the experiment worktree, or a fresh worktree at the base with the prototype patch applied. Ensure no active Gradle invocation, dev client or other solver campaign; idle Gradle daemons are harmless. Compile first, wait for Gradle to finish, then run the campaign outside Gradle.

```powershell
$toolPath = 'D:/Minecraft/Modding/1.21/CreateChemE/tools/pipe-thermal-loss'
.\gradlew.bat -I "$toolPath/study.init.gradle" preparePipeThermalStudy fluidScienceTest --console=plain --no-configuration-cache
$studyJava = (Get-Content build/pipe-thermal-study/java.txt -Raw).Trim()
$studyClasspath = Get-Content build/pipe-thermal-study/classpath.txt -Raw
# Choose new output directories; retain the original evidence.
& $studyJava '-Xmx3G' '-cp' $studyClasspath 'com.wormzjl.createcheme.study.PipeThermalStudy' 'D:/Minecraft/Modding/1.21/CreateChemE/research/2026-09-24-pipe-thermal-loss/campaign-02'
& $studyJava '-Xmx3G' '-cp' $studyClasspath 'com.wormzjl.createcheme.study.PipeThermalFollowup' 'D:/Minecraft/Modding/1.21/CreateChemE/research/2026-09-24-pipe-thermal-loss/campaign-02/cases.csv' 'D:/Minecraft/Modding/1.21/CreateChemE/research/2026-09-24-pipe-thermal-loss/followup-02'
# Original evidence summary:
& "$toolPath/analyze.ps1"
```

`analyze.ps1` intentionally reads campaign-01/followup-01 under its DataRoot. To summarize another run without editing originals, arrange a separate DataRoot with those directory names or adapt the script.

## Reference and timing interpretation

All methods use prescribed U and a fixed representative thermodynamic pressure. Only cases with adiabatic pressure drop <=5% of that pressure enter the comparison. Hydraulics use the repository's homogeneous Darcy model, not two-phase slip flow. The reference couples the spatial cooling profile and pipe resistance by scalar bracketing, with a refined thermodynamic curve. This is a controlled steady-line surrogate, not PassiveStepSolver integration.

Thermal-only errors compare at the SAME reference mass flow; hydraulic correction errors allow mass flow to differ. Do not combine those claims. `SCREENED_DP` cases are explicit exclusions, not solver successes. Pilot steam at 200 kPa failed an existing water-vapour property guard and is retained as failed evidence; campaign steam is at supported 100 kPa.

Per-case thread CPU times can be zero or quantized on this Windows runtime. Use `campaign-01/timings.csv`: warmed serial wall time averaged over 10,000 exchange calls, 1,000 one-correction calls or 100 reference calls, repeated five times. The measured profile-build figure includes new model construction; it is not isolated interpolation-table cost. Neither timings nor accuracy qualify game/network performance.

See the batch experiment review for the results and limitations.

# JDK determinism of the fluid solver: sweep, diagnosis, fix

Batch `2026-09-27-jdk-determinism`, started 2026-09-27. Branch `claude/jdk-determinism-wip` from `efd123a` (phase-ports
batch close), worktree `scratchpad/wt-jdk` of session `cfcc6b94`. Author: Claude (Opus 5.5), for the owner's requirement
of 2026-09-27, verbatim: "I need the result to be identical across various JDKs on the market. Perform a sweep, find out
diff, propose fix."

Commits: `Study: JDK determinism sweep of the fluid solver` (tools, captures, this review) and `WIP jdk-determinism:
DeterministicMath routing prototype` (code, tests, re-baselines). Not pushed, not merged, no version bump.

## 1. Answer in short

- **Sweep.** 36 Linux x64 runtimes: HotSpot 21 GA to 27 from 11 vendors (Temurin 21.0.2/.5/.8/.10/.11/.12.1, 22, 23,
  24, 25, 26, 27; Zulu, Corretto, Microsoft, Liberica, Oracle JDK, Oracle OpenJDK, SapMachine, Dragonwell, Kona,
  Ubuntu), GraalVM CE and Oracle GraalVM 21 and 25 (Graal JIT), IBM Semeru (OpenJ9) 21.0.5, 21.0.10, 25.0.2. One build of
  the fluid code (compiled once with the container's javac) ran under each: the 249 fluid runtime tests with the 33
  junction ledger lines, chain-100 exact, the d9 BitwiseProbe (chain-100 plus 24 scenarios, every double in hex), and a
  `Math` vs `StrictMath` micro-sweep of 1,048,576 inputs per function.
- **Diff.** Three result families, each byte-identical inside:
  **A** every HotSpot 21, 22, 23, 24 build of every vendor and GraalVM 21 (22 runtimes);
  **B** every HotSpot 25, 26, 27 build and GraalVM 25 (11 runtimes): 24 of the 33 junction lines, 2,721 of the 3,003
  chain-100 numbers (max 8.0e-15 relative) and 1,796 + 418 + 252 probe doubles differ from A;
  **C** every OpenJ9 (3 runtimes): 28 junction lines, 2,719 chain-100 numbers (max 5.7e-15 relative), 2,077 + 482 + 247
  probe doubles differ from A. Tests pass everywhere (249/249); the fluid assertions are tolerance-based, the outputs
  are not.
- **Cause: the libm, and only the libm.** HotSpot replaces `Math.log, exp, pow, cos, log10` by CPU intrinsics that differ
  from fdlibm by 1 ulp (log10 2 ulp) on 2.7 to 8.4 % of the sampled inputs; JDK 25 added a `Math.cbrt` intrinsic (87,287 of 1,048,576
  inputs differ by 1 ulp), which is exactly family B; OpenJ9's `Math` returns the fdlibm (`StrictMath`) values for all
  eleven functions, and HotSpot with `-XX:-UseLibmIntrinsic` reproduces OpenJ9's outputs byte for byte (family C), so
  the libm explains every difference found. `Math.sqrt` and `Math.fma` are identical on all 36 runtimes, as the JLS
  requires. On GraalVM CE 25 the Graal-compiled `Math.pow` differs from the interpreter's on 7 of 1,048,576 inputs: the
  result there even depends on warm-up. The owner's Windows JDK 21.0.11 line (the one `MIXED_GAS_TRANSIENT` value) cannot
  be reproduced by any Linux runtime, including Temurin 21.0.11; it is inferred to be a platform path of the same
  functions (section 7.3).
- **Fix.** Route every non-correctly-rounded function of the fluid code through one facade,
  `science/math/DeterministicMath`, which returns the `StrictMath` (fdlibm) result bit for bit; keep `Math.sqrt`/`fma`
  and all exact `Math` functions; a source-scan test in `fluidScienceTest` fails on any direct `Math.log/exp/pow/...`
  (or `StrictMath.*`) call in the fluid packages. The facade, not direct `StrictMath` calls, because `StrictMath.pow`
  on JDK 21 to 27 allocates 96 bytes per call and costs 7x `Math.pow`: the facade holds an allocation-free
  transliteration of fdlibm `e_pow.c` that is bitwise equal to `StrictMath.pow` (1.5 billion inputs on five runtimes, 0
  differences).
- **Prototype result.** On all 36 runtimes the prototype's outputs are byte-identical: the 33 junction lines, all four
  BitwiseProbe files (36 default runs and the 33 runs with `-XX:-UseLibmIntrinsic`, which no longer change anything),
  chain-100 exact at 0.000e+00 against the re-captured reference, 249/249 runtime tests; and the new outputs equal what
  OpenJ9 produced before the change, bit for bit.
- **Cost** (container JDK, `harness.sh runtime`, 3 alternating runs): wall 54.8 s before, 64.2 s with the facade (+17 %);
  `MIXED_GAS_COST` 449 ms before, 655 ms with the facade (+46 %), same 286 Newton solves, allocation unchanged. Direct
  `StrictMath` would cost +33 % wall, +90 % `MIXED_GAS_COST` and 4.2x allocation. The whole remaining cost is `pow`
  (42 ns vs 12 ns per call); an optional strength reduction of four hot `pow` calls with integer exponents
  (`tools/jdk-determinism/pow-strength-reduction.patch`, not in the prototype) brings both numbers back to the baseline
  (53.8 s, 445 ms).
- **Re-baseline** (one time, fresh world rules apply): 28 of the 33 junction lines, `chain-100.json`
  (max 5.7e-15 relative moles, 3.4e-13 K; same 3 accepted steps and 29 Newton iterations), and printed diagnostics of
  20 fluid test classes. No fluid assertion or tolerance changed; every moved fluid output is bitwise the output OpenJ9
  produced before the change (section 10.5).
- **Column.** The column V3 solver is not deterministic today either: on base code its test suite passes on HotSpot
  21 (the development JDK), while OpenJ9 21 fails 4 column tests and HotSpot 25 fails 3 different ones. The prototype
  routes only the column file the fluid network executes (`V3WaterProperties`); that fails 2 column tests in the Gradle
  full suite (a hybrid libm in the column that no runtime had before). Routing the whole column
  (`tools/jdk-determinism/full-science-routing.patch`) makes it deterministic and fails exactly the 4 tests OpenJ9
  fails today. Owner decision (section 12).

## 2. Method

- **One build, many runtimes.** `tools/cloud-science-harness/harness.sh` gained a `JAVA=` override (javac stays the
  container JDK's), so the class files are the same for every runtime. Before: `efd123a` compiled into
  `scratchpad/jdk-sweep/out-before`. After: the prototype compiled into `out-after`.
- **Per runtime** (`tools/jdk-determinism/sweep.sh`): `harness.sh runtime` (249 tests of `fluidRuntimeTest`, the 33
  `MIXED_GAS_*`/`LIQUID_JUNCTION` lines normalised by stripping wall ms, bytes, allocatedMB), `harness.sh regression`
  (the real `FluidSolverRegressionTest`, chain-100 exact against the checked-in reference), the d9 BitwiseProbe
  (`chain-100.json`, `scenarios.txt` 14 all-BULK scenarios, `gas-ports.txt` 8 gas-port scenarios, `liquid-ports.txt`
  2 liquid-port scenarios; every double in hex), the probe again with `-XX:+UnlockDiagnosticVMOptions
  -XX:-UseLibmIntrinsic` on HotSpot, and `MathSweep` (default JIT with 3 repetitions reported last, `-Xint`, and on
  HotSpot `-XX:-UseLibmIntrinsic`; OpenJ9 silently ignores unknown `-XX` options, so that mode is skipped there).
- **MathSweep** (`tools/jdk-determinism/src/MathSweep.java`): for `log, exp, pow, cbrt, log1p, expm1, cos, log10, acos,
  sqrt, fma`, 1,048,576 inputs each over the ranges the thermodynamics uses (pressures 1e2-1e7 Pa, temperatures
  200-1000 K, mole fractions 1e-14-1, reduced quantities, Z factors, near-1 arguments, near-zero and negative arguments
  for `log1p`/`expm1`, `pow` with the code's fractional and integer exponents and `pow(10, e)`, Cardano `cbrt`
  arguments of both signs, trigonometric cubic-root angles, `acos` near +-1, `fma` with cancellation). Inputs come from
  SplitMix64 and exact bit operations only; the per-function input hash is identical on all 36 runtimes, and so is
  every `StrictMath` output hash (11 distinct (function, hash) pairs over all runtimes and modes).
- **Comparison** (`tools/jdk-determinism/compare.py`): groups runtimes by byte-identical output; counts differing
  values against the reference runtime (`system`, the container's OpenJDK 21.0.10).
- **Gradle** remains the gate of record (section 11); the harness runs stand in only for runtimes Gradle cannot use here.

## 3. Runtimes

All Linux x64, fetched by `tools/jdk-determinism/fetch-jdks.sh` from the vendor endpoints (Adoptium API, Azul
metadata API, corretto.aws, aka.ms, BellSoft API, jdk.java.net, download.oracle.com, GitHub releases of GraalVM CE and
IBM Semeru; SapMachine, Dragonwell, Kona, Oracle OpenJDK 21.0.2/25 and GraalVM CE 25 resolved through the foojay disco
index to the vendor's own URL). The vendor checksum was verified for every tarball except Dragonwell (none published)
and Kona (the index gives the literal text "MD5" instead of a digest); the sha256 below is of the file as downloaded.
Manifest: `tools/jdk-determinism/captures/jdks-manifest.tsv`.

| id | vendor | JVM (JIT) | java.runtime.version | download URL | sha256 of the tarball |
|---|---|---|---|---|---|
| system | Ubuntu (container) | HotSpot (C2) | 21.0.10+7-Ubuntu-124.04 | apt `openjdk-21-jdk-headless` | - |
| corretto-21 | Amazon Corretto | HotSpot (C2) | 21.0.12.1+12-LTS | https://corretto.aws/downloads/latest/amazon-corretto-21-x64-linux-jdk.tar.gz | `8785082c2fb999c024c8821e4a7c5391bda28f1667cceadafd78e2965b7669d2` |
| corretto-25 | Amazon Corretto | HotSpot (C2) | 25.0.4.1+10-LTS | https://corretto.aws/downloads/latest/amazon-corretto-25-x64-linux-jdk.tar.gz | `a45f1d385da8221fe1225dde5b6afa8e932a6acb93571b55eff4a39a2ebe7378` |
| dragonwell-21 | Alibaba Dragonwell | HotSpot (C2) | 21.0.12.0.12 | https://github.com/dragonwell-project/dragonwell21/releases/download/dragonwell-standard-21.0.12.0.12%2B8_jdk-21.0.12-ga/Alibaba_Dragonwell_Standard_21.0.12.0.12.8_x64_linux.tar.gz | `16842c9422323c3e0f047e543a3183997c39723061e4eff2816ff9a93baa0694` |
| graalvm-ce-21 | GraalVM Community | HotSpot + Graal JIT (libgraal) | 21.0.2+13-jvmci-23.1-b30 | https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-21.0.2/graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz | `b048069aaa3a99b84f5b957b162cc181a32a4330cbc35402766363c5be76ae48` |
| graalvm-ce-25 | GraalVM Community | HotSpot + Graal JIT (libgraal) | 25.0.4.1+1-jvmci-25.3-b22 | https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.3.4.1/graalvm-community-jdk-25i3-25.0.4.1_linux-x64_bin.tar.gz | `b2bc38d0c4141426eb44d0eefa3cc172c96faf92727d703b61541699128b6fc7` |
| kona-21 | Tencent Kona | HotSpot (C2) | 21.0.12+1-LTS | https://github.com/Tencent/TencentKona-21/releases/download/TencentKona-21.0.12/TencentKona-21.0.12.b1-jdk_linux-x86_64.tar.gz | `22ee6933c7110af5814edcdae25c85605cf7dd08f94419b7c6fa9ff7f0065f2e` |
| liberica-21 | BellSoft Liberica | HotSpot (C2) | 21.0.12.1+1-LTS | https://github.com/bell-sw/Liberica/releases/download/21.0.12.1+1/bellsoft-jdk21.0.12.1+1-linux-amd64.tar.gz | `94ae18a66527a54ebaf4490279e03631fcc47dbf262863f099ac9c8f5f525a49` |
| liberica-25 | BellSoft Liberica | HotSpot (C2) | 25.0.4.1+1-LTS | https://github.com/bell-sw/Liberica/releases/download/25.0.4.1+1/bellsoft-jdk25.0.4.1+1-linux-amd64.tar.gz | `acb5cc5abdc2baeaecfa3ec5ba5609bea67121eb99004b27036d397f4a79c152` |
| microsoft-21 | Microsoft Build of OpenJDK | HotSpot (C2) | 21.0.12.1+1-LTS | https://aka.ms/download-jdk/microsoft-jdk-21-linux-x64.tar.gz | `4c0c7f5cd0b6bb81109d01f13a1678ee0f73f36c1020080d97c4de3ce3cac207` |
| microsoft-25 | Microsoft Build of OpenJDK | HotSpot (C2) | 25.0.4.1+1-LTS | https://aka.ms/download-jdk/microsoft-jdk-25-linux-x64.tar.gz | `d3b07dd6fd096353d6834e62a6f32117eb610d0bee24cb70acb8c82832043b68` |
| oracle-graalvm-21 | Oracle GraalVM | HotSpot + Graal JIT (libgraal) | 21.0.12+7-LTS-jvmci-23.1-b96 | https://download.oracle.com/graalvm/21/latest/graalvm-jdk-21_linux-x64_bin.tar.gz | `b007ff64c425f85bbe0e686107044fba6ca5054a7e89271a473767f546aaddc1` |
| oracle-graalvm-25 | Oracle GraalVM | HotSpot + Graal JIT (libgraal) | 25.0.4+7-LTS-jvmci-b01 | https://download.oracle.com/graalvm/25/latest/graalvm-jdk-25_linux-x64_bin.tar.gz | `76007c309f821aaf435bce63162ea0395587fc77350801c81643fe7feea37276` |
| oracle-jdk-21 | Oracle JDK | HotSpot (C2) | 21.0.12.1+1-LTS-4 | https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz | `12f870b21301b42292558a3f872ce543affa2b86cb6458591c78388c41ddb111` |
| oracle-jdk-25 | Oracle JDK | HotSpot (C2) | 25.0.4.1+1-LTS-5 | https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz | `dd7e7f51d23bcffe85f1a439577ce1c0190a33417b34afdb338f9b3897bdeaec` |
| oracle-openjdk-21 | Oracle OpenJDK (jdk.java.net) | HotSpot (C2) | 21+35-2513 | https://download.java.net/java/GA/jdk21/fd2272bbf8e04c3dbaee13770090416c/35/GPL/openjdk-21_linux-x64_bin.tar.gz | `a30c454a9bef8f46d5f1bf3122830014a8fbe7ac03b5f8729bc3add4b92a1d0a` |
| oracle-openjdk-21.0.2 | Oracle OpenJDK (jdk.java.net) | HotSpot (C2) | 21.0.2+13-58 | https://download.java.net/java/GA/jdk21.0.2/f2283984656d49d69e91c558476027ac/13/GPL/openjdk-21.0.2_linux-x64_bin.tar.gz | `a2def047a73941e01a73739f92755f86b895811afb1f91243db214cff5bdac3f` |
| oracle-openjdk-25 | Oracle OpenJDK (jdk.java.net) | HotSpot (C2) | 25.0.2+10-69 | https://download.java.net/java/GA/jdk25.0.2/b1e0dfa218384cb9959bdcb897162d4e/10/GPL/openjdk-25.0.2_linux-x64_bin.tar.gz | `555ce0821e4fe175ea50d54518cd6fbece9663c1998de529bc6ce429534457df` |
| sapmachine-21 | SAP SapMachine | HotSpot (C2) | 21.0.12.1+1-LTS | https://github.com/SAP/SapMachine/releases/download/sapmachine-21.0.12.1/sapmachine-jdk-21.0.12.1_linux-x64_bin.tar.gz | `e51e90fa8111f6293b69888d7c6b6cf9791942c84327057a2f899c619c3f7cb8` |
| semeru-21.0.10 | IBM Semeru Open Edition | OpenJ9 (Testarossa) | 21.0.10+7-LTS | https://github.com/ibmruntimes/semeru21-binaries/releases/download/jdk-21.0.10%2B7_openj9-0.57.0/ibm-semeru-open-jdk_x64_linux_21.0.10_7_openj9-0.57.0.tar.gz | `f7f971225362cac4170f601795da97371746ccddc9fa6ea937b83f08bb901b03` |
| semeru-21.0.5 | IBM Semeru Open Edition | OpenJ9 (Testarossa) | 21.0.5+11-LTS | https://github.com/ibmruntimes/semeru21-binaries/releases/download/jdk-21.0.5%2B11_openj9-0.48.0/ibm-semeru-open-jdk_x64_linux_21.0.5_11_openj9-0.48.0.tar.gz | `ccca1486fd445a7500881e3e43eda58a6b26e4c2efa2926b7a67aeff6d114514` |
| semeru-25.0.2 | IBM Semeru Open Edition | OpenJ9 (Testarossa) | 25.0.2+10-LTS | https://github.com/ibmruntimes/semeru25-binaries/releases/download/jdk-25.0.2%2B10_openj9-0.57.0/ibm-semeru-open-jdk_x64_linux_25.0.2_10_openj9-0.57.0.tar.gz | `02c67106e35eb9bc02fd78f527d3ff07b903332579aeee273bd056a98748fd6b` |
| temurin-21-latest | Eclipse Temurin | HotSpot (C2) | 21.0.12.1+1-LTS | https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz | `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94` |
| temurin-21.0.10 | Eclipse Temurin | HotSpot (C2) | 21.0.10+7-LTS | https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.10%2B7/OpenJDK21U-jdk_x64_linux_hotspot_21.0.10_7.tar.gz | `ea3b9bd464d6dd253e9a7accf59f7ccd2a36e4aa69640b7251e3370caef896a4` |
| temurin-21.0.11 | Eclipse Temurin | HotSpot (C2) | 21.0.11+10-LTS | https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.11_10.tar.gz | `4b2220e232a97997b436ca6ab15cbf70171ecff52958a46159dfa5a8c44ca4de` |
| temurin-21.0.2 | Eclipse Temurin | HotSpot (C2) | 21.0.2+13-LTS | https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.2%2B13/OpenJDK21U-jdk_x64_linux_hotspot_21.0.2_13.tar.gz | `454bebb2c9fe48d981341461ffb6bf1017c7b7c6e15c6b0c29b959194ba3aaa5` |
| temurin-21.0.5 | Eclipse Temurin | HotSpot (C2) | 21.0.5+11-LTS | https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz | `3c654d98404c073b8a7e66bffb27f4ae3e7ede47d13284c132d40a83144bfd8c` |
| temurin-21.0.8 | Eclipse Temurin | HotSpot (C2) | 21.0.8+9-LTS | https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.8_9.tar.gz | `f2dc5418092c43003db8f9005c4a286e1c0104fea96ccdd49e8ebd037cac9219` |
| temurin-22 | Eclipse Temurin | HotSpot (C2) | 22.0.2+9 | https://github.com/adoptium/temurin22-binaries/releases/download/jdk-22.0.2%2B9/OpenJDK22U-jdk_x64_linux_hotspot_22.0.2_9.tar.gz | `05cd9359dacb1a1730f7c54f57e0fed47942a5292eb56a3a0ee6b13b87457a43` |
| temurin-23 | Eclipse Temurin | HotSpot (C2) | 23.0.2+7 | https://github.com/adoptium/temurin23-binaries/releases/download/jdk-23.0.2%2B7/OpenJDK23U-jdk_x64_linux_hotspot_23.0.2_7.tar.gz | `870ac8c05c6fe563e7a3878a47d0234b83c050e83651d2c47e8b822ec74512dd` |
| temurin-24 | Eclipse Temurin | HotSpot (C2) | 24.0.2+12 | https://github.com/adoptium/temurin24-binaries/releases/download/jdk-24.0.2%2B12/OpenJDK24U-jdk_x64_linux_hotspot_24.0.2_12.tar.gz | `aea1cc55e51cf651c85f2f00ad021603fe269c4bb6493fa97a321ad770c9b096` |
| temurin-25 | Eclipse Temurin | HotSpot (C2) | 25.0.4.1+1-LTS | https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz | `dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e` |
| temurin-26 | Eclipse Temurin | HotSpot (C2) | 26.0.2.1+1 | https://github.com/adoptium/temurin26-binaries/releases/download/jdk-26.0.2.1%2B1/OpenJDK26U-jdk_x64_linux_hotspot_26.0.2.1_1.tar.gz | `451c12e68747bcfa2fb5a2c16b00483fedb9fa6d77bc962d30957f76ac17044d` |
| temurin-27 | Eclipse Temurin | HotSpot (C2) | 27+35 | https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27%2B35/OpenJDK27U-jdk_x64_linux_hotspot_27_35.tar.gz | `1cf69a4848ffb728b3b260dfd45206a51566ab571a02a30092271d4c580bccbc` |
| zulu-21 | Azul Zulu | HotSpot (C2) | 21.0.12.1+1-LTS | https://cdn.azul.com/zulu/bin/zulu21.52.203-ca-jdk21.0.12.1-linux_x64.tar.gz | `db0c11e13b545e64d520b4821f4ca38ea9bc1c515924eb1e7f48435df101f183` |
| zulu-25 | Azul Zulu | HotSpot (C2) | 25.0.4.1+1-LTS | https://cdn.azul.com/zulu/bin/zulu25.36.205-ca-jdk25.0.4.1-linux_x64.tar.gz | `e11d92589de8fd55616a843e0298ba72348848bd49b675088e930b67538497cb` |

Not covered: Windows, macOS, aarch64, other OpenJ9 builds than the three above (section 13).

## 4. Results before the change

### 4.1 Per runtime

| id | java.runtime.version | VM | runtime tests | junction lines | chain-100 exact | probe (4 files) | probe -UseLibmIntrinsic |
|---|---|---|---|---|---|---|---|
| system | 21.0.10+7-Ubuntu-124.04 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| corretto-21 | 21.0.12.1+12-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| corretto-25 | 25.0.4.1+10-LTS | HotSpot | 249/0 ok/failed | B | 8.012e-15 | B | C |
| dragonwell-21 | 21.0.12.0.12 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| graalvm-ce-21 | 21.0.2+13-jvmci-23.1-b30 | HotSpot + Graal JIT | 249/0 ok/failed | A | 0.000e+00 | A | D |
| graalvm-ce-25 | 25.0.4.1+1-jvmci-25.3-b22 | HotSpot + Graal JIT | 249/0 ok/failed | B | 8.012e-15 | B | E |
| kona-21 | 21.0.12+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| liberica-21 | 21.0.12.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| liberica-25 | 25.0.4.1+1-LTS | HotSpot | 249/0 ok/failed | B | 8.012e-15 | B | C |
| microsoft-21 | 21.0.12.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| microsoft-25 | 25.0.4.1+1-LTS | HotSpot | 249/0 ok/failed | B | 8.012e-15 | B | C |
| oracle-graalvm-21 | 21.0.12+7-LTS-jvmci-23.1-b96 | HotSpot (Oracle) + Graal JIT | 249/0 ok/failed | A | 0.000e+00 | A | F |
| oracle-graalvm-25 | 25.0.4+7-LTS-jvmci-b01 | HotSpot (Oracle) + Graal JIT | 249/0 ok/failed | B | 8.012e-15 | B | G |
| oracle-jdk-21 | 21.0.12.1+1-LTS-4 | HotSpot (Oracle) | 249/0 ok/failed | A | 0.000e+00 | A | C |
| oracle-jdk-25 | 25.0.4.1+1-LTS-5 | HotSpot (Oracle) | 249/0 ok/failed | B | 8.012e-15 | B | C |
| oracle-openjdk-21 | 21+35-2513 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| oracle-openjdk-21.0.2 | 21.0.2+13-58 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| oracle-openjdk-25 | 25.0.2+10-69 | HotSpot | 249/0 ok/failed | B | 8.012e-15 | B | C |
| sapmachine-21 | 21.0.12.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| semeru-21.0.10 | 21.0.10+7-LTS | OpenJ9 | 249/0 ok/failed | C | 5.657e-15 | C | n/a |
| semeru-21.0.5 | 21.0.5+11-LTS | OpenJ9 | 249/0 ok/failed | C | 5.657e-15 | C | n/a |
| semeru-25.0.2 | 25.0.2+10-LTS | OpenJ9 | 249/0 ok/failed | C | 5.657e-15 | C | n/a |
| temurin-21-latest | 21.0.12.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| temurin-21.0.10 | 21.0.10+7-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| temurin-21.0.11 | 21.0.11+10-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| temurin-21.0.2 | 21.0.2+13-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| temurin-21.0.5 | 21.0.5+11-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| temurin-21.0.8 | 21.0.8+9-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| temurin-22 | 22.0.2+9 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| temurin-23 | 23.0.2+7 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| temurin-24 | 24.0.2+12 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| temurin-25 | 25.0.4.1+1-LTS | HotSpot | 249/0 ok/failed | B | 8.012e-15 | B | C |
| temurin-26 | 26.0.2.1+1 | HotSpot | 249/0 ok/failed | B | 8.012e-15 | B | C |
| temurin-27 | 27+35 | HotSpot | 249/0 ok/failed | B | 8.012e-15 | B | C |
| zulu-21 | 21.0.12.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | C |
| zulu-25 | 25.0.4.1+1-LTS | HotSpot | 249/0 ok/failed | B | 8.012e-15 | B | C |

Letters name byte-identical groups; the probe column compares all four probe files. `chain-100 exact` is the maximum
relative moles deviation reported by `harness.sh regression` against the checked-in `chain-100.json` (recorded on
HotSpot 21). Every runtime passed 249/249 runtime tests and the regression test's own exact comparison failed only where
the deviation is nonzero (families B and C).

### 4.2 What differs

| family | runtimes | junction lines differing from A | chain-100 numbers differing | chain-100 max deviation | scenarios.txt | gas-ports.txt | liquid-ports.txt |
|---|---|---|---|---|---|---|---|
| A | HotSpot 21 GA to 24.0.2, all vendors, GraalVM 21 (22) | 0 of 33 | 0 of 3,003 | 0 | 0 of 3,460 | 0 of 997 | 0 of 608 |
| B | HotSpot 25.0.2 to 27, all vendors, GraalVM 25 (11) | 24 of 33 (all 24 `MIXED_GAS_TRANSIENT`) | 2,721 | moles 8.0e-15 rel., T 2.8e-13 K, phase fraction 3.9e-16, flow 6.8e-11 rel. (2.9e-14 kg/s) | 1,796 | 418 | 252 |
| C | OpenJ9 21.0.5, 21.0.10, 25.0.2 (3) | 28 of 33 (24 transients + 4 liquid tank lines) | 2,719 | moles 5.7e-15 rel., T 3.4e-13 K, phase fraction 3.3e-16, flow 1.8e-11 rel. (2.7e-14 kg/s) | 2,077 | 482 | 247 |
| C | = every HotSpot C2 runtime of A and B with `-XX:-UseLibmIntrinsic` (29) | byte-identical to OpenJ9 | | | | | |

Newton solve counts are the same everywhere (286 at 5 s for the mixed-gas cases, 13/13/17/17 for the liquid cases), and
chain-100 takes the same 3 accepted steps everywhere. The magnitudes are 1-ulp inputs propagated through Newton solves
and 20 s of transient: ledger values at 1e-16 to 1e-15 (gate 1e-12), states at 1e-15 relative. Two printed quantities
move by more, both ratios of roundoff-level numbers: the `LIQUID_JUNCTION tanks` lines at 0.1 s report an inflow of
3e-12 kg/s (a stagnant junction whose flow is roundoff) and a mismatch relative to it (-0.427 on A, +0.00294 on C); the
tank lines at 5 s report the junction's enthalpy mismatch after each slice, which freezes after the first slice once the
two tanks have equalised and the through-flow has stopped (-0.472 on A, +0.00295 on C for 50 mm; -0.0806 and -0.894
for 20 mm), so it is a ratio of stagnation residues. Their ledgers close at 1e-12 on every runtime; they are printed,
not asserted.

Probe groups D-G are GraalVM with `-XX:-UseLibmIntrinsic`: the flag switches off HotSpot's stubs but not the Graal JIT's
own intrinsics, so interpreted and compiled calls return different libms and each GraalVM build lands in its own group.

The full per-group junction diffs are in `tools/jdk-determinism/captures/before/comparison.md`.

## 5. MathSweep: `Math` vs `StrictMath` per function

Cell: inputs out of 1,048,576 where `Math.f != StrictMath.f` (bitwise) / maximum ulp difference. "0" = identical to
`StrictMath` on every input. Within a family the `Math` outputs are byte-identical across all its builds and vendors
(one output hash per function), and `-Xint` gives the same outputs as the JIT on every HotSpot build.

| function | HotSpot 21-24, GraalVM 21 (22 runtimes) | HotSpot 25-27, GraalVM 25 (11) | OpenJ9 (3) | HotSpot with `-XX:-UseLibmIntrinsic` |
|---|---|---|---|---|
| log | 28,115 / 1 | 28,115 / 1 | 0 | 0 |
| exp | 80,844 / 1 | 80,844 / 1 | 0 | 0 |
| pow | 87,714 / 1 | 87,714 / 1 (GraalVM CE 25 JIT: 87,715, see below) | 0 | 0 |
| cbrt | 0 | **87,287 / 1** (new HotSpot intrinsic in JDK 25) | 0 | 0 |
| log1p | 0 | 0 | 0 | 0 |
| expm1 | 0 | 0 | 0 | 0 |
| cos | 30,675 / 1 | 30,675 / 1 | 0 | 0 |
| log10 | 69,214 / 2 | 69,214 / 2 | 0 | 0 |
| acos | 0 | 0 | 0 | 0 |
| sqrt | 0 | 0 | 0 | 0 |
| fma | 0 | 0 | 0 | 0 |

- `StrictMath` gives the same 11 output hashes on all 36 runtimes in every mode, and so does `Math.sqrt` and `Math.fma`
  (correctly rounded by the JLS; OpenJ9 and Graal included). They stay as they are.
- `log1p`, `expm1`, `acos` (and `cbrt` before JDK 25) are `Math` methods that currently delegate to `StrictMath` on
  every runtime tested; the specification allows an intrinsic at any time (JDK 25 did it for `cbrt`), so they are
  routed too.
- **Tier dependence on GraalVM CE 25.** The Graal-compiled `Math.pow` returns 1-ulp different results from the
  interpreter's on 7 of the 1,048,576 inputs (`captures/tier/graalvm-ce-25-pow-xint-vs-jit.diff`), so the same JVM
  returns different results for the same call before and after warm-up. With `-XX:-UseLibmIntrinsic`, every GraalVM
  build is tier-dependent for `log, exp, pow, cos, log10` (the flag reaches the interpreter, not Graal).
- Measured JDK 21.0.10 `StrictMath` source: `java.lang.StrictMath` and `java.lang.Math` have no native methods (javap),
  so `StrictMath` is Java bytecode on every platform.

Full tables per runtime and mode: `captures/before/comparison.md`; raw outputs `captures/before/<id>/mathsweep-*.txt`.

## 6. Call-site map

172 call sites of non-correctly-rounded `Math` functions exist in `src/main/java`, all under `science/` (none in
`runtime/`, `network/`, `world/`, `client/`). To find the ones the fluid results depend on, a one-off copy of the tree had
every such call rewritten to a counter keyed by file:line:function and ran the harness science (223), runtime (249),
adjacent (45) and regression gates and the BitwiseProbe (`tools/jdk-determinism/site-counts/`). 65 sites in 13 files
execute; no other column file does.

| file | sites (function x count) | executed by the fluid gates (sites, calls) |
|---|---|---|
| science/column/v3/HollandB12Thermo.java | log 1 | 0 sites, 0 calls |
| science/column/v3/IndependentHollandMeshOracle.java | exp 2, log 4 | 0 sites, 0 calls |
| science/column/v3/V3AcceptanceAuditor.java | exp 1 | 0 sites, 0 calls |
| science/column/v3/V3AnchorTransformerInitializer.java | exp 2 | 0 sites, 0 calls |
| science/column/v3/V3ColumnCalculator.java | log10 1, pow 1 | 0 sites, 0 calls |
| science/column/v3/V3ColumnInitializer.java | exp 3, log 6 | 0 sites, 0 calls |
| science/column/v3/V3DryMeshCoordinateMap.java | exp 2, log 2 | 0 sites, 0 calls |
| science/column/v3/V3FactorizedNeuralFeatures.java | exp 1, expm1 2, log 4, log1p 3 | 0 sites, 0 calls |
| science/column/v3/V3FreeWaterContinuation.java | log 1 | 0 sites, 0 calls |
| science/column/v3/V3GeneralNeuralFeatures.java | expm1 1, log 1, log1p 3 | 0 sites, 0 calls |
| science/column/v3/V3HollandExample32.java | exp 2 | 0 sites, 0 calls |
| science/column/v3/V3MeshResidualEvaluator.java | log 7 | 0 sites, 0 calls |
| science/column/v3/V3StageEquilibriumRatios.java | exp 1 | 0 sites, 0 calls |
| science/column/v3/V3TrayHydraulics.java | cbrt 1, exp 3, pow 8 | 0 sites, 0 calls |
| science/column/v3/thermo/V3FeedFlash.java | exp 3, expm1 2, log 1 | 0 sites, 0 calls |
| science/column/v3/thermo/V3PropertyComponent.java | pow 2 | 0 sites, 0 calls |
| science/column/v3/thermo/V3TruncatedFlash.java | exp 2, expm1 1, log 7 | 0 sites, 0 calls |
| science/column/v3/thermo/V3WaterProperties.java | exp 1, pow 10 | 5 sites, 5,391,608 calls |
| science/fluid/network/FlowControl.java | log1p 1 | 1 sites, 246,272 calls |
| science/fluid/network/PipeResistance.java | log 1, log10 1, pow 4 | 6 sites, 355,951,116 calls |
| science/fluid/solver/PhaseLayout.java | exp 6, log 15 | 21 sites, 34,344,775 calls |
| science/fluid/thermo/FluidThermodynamics.java | exp 3, log 5 | 8 sites, 766,146,618 calls |
| science/fluid/thermo/GlobalLiquidResponse.java | exp 1, expm1 1, log 2 | 4 sites, 266,031,104 calls |
| science/fluid/thermo/HydrocarbonModel.java | exp 1 | 1 sites, 9,187 calls |
| science/fluid/thermo/TranslatedPengRobinson.java | log 1, pow 2 | 3 sites, 80,183,820 calls |
| science/fluid/transport/MixtureViscosity.java | exp 1, log 1 | 2 sites, 1,717,114 calls |
| science/fluid/transport/SlurryTransport.java | pow 1 | 1 sites, 69,455 calls |
| science/material/ViscosityCorrelation.java | exp 2, log 4, pow 2 | 5 sites, 53,667,216 calls |
| science/thermo/PengRobinson78.java | acos 1, cbrt 3, cos 3, exp 1, log 3 | 8 sites, 229,048 calls |
| science/thermo/PengRobinsonKernel.java | acos 1, cbrt 5, cos 3, exp 1, log 4 | 10 sites, 160,367,833 calls |

Executed sites (line: function, calls over science + runtime + adjacent + regression + probe):

- science/column/v3/thermo/V3WaterProperties.java: 44 pow 1,347,788; 45 pow 1,347,788; 46 pow 1,347,788; 47 exp 1,347,788; 138 pow 456
- science/fluid/network/FlowControl.java: 94 log1p 246,272
- science/fluid/network/PipeResistance.java: 33 pow 71,603,017; 36 pow 40,621,157; 37 log10 40,621,157; 38 log 40,621,157; 38 pow 81,242,314; 38 pow 81,242,314
- science/fluid/solver/PhaseLayout.java: 130 exp 2,681,814; 153 log 1,057,098; 153 log 1,057,098; 156 log 6,628; 163 log 33,665; 173 log 2,153,002; 173 log 2,153,002; 174 log 228,848; 188 exp 994,934; 189 exp 1,460,350; 198 exp 5,363,686; 198 exp 5,363,686; 199 exp 833,521; 280 log 9,312; 315 log 5,065,182; 315 log 5,065,182; 317 log 753,634; 348 log 9,022; 349 log 9,022; 354 log 45,981; 355 log 108
- science/fluid/thermo/FluidThermodynamics.java: 490 exp 7,850,712; 490 log 7,850,712; 506 log 235,537,138; 511 exp 220,925,878; 511 log 220,925,878; 513 exp 14,611,260; 513 log 29,222,520; 513 log 29,222,520
- science/fluid/thermo/GlobalLiquidResponse.java: 33 exp 13,401,163; 34 expm1 13,401,163; 51 log 3,691,640; 64 log 235,537,138
- science/fluid/thermo/HydrocarbonModel.java: 53 exp 9,187
- science/fluid/thermo/TranslatedPengRobinson.java: 316 pow 26,727,940; 317 pow 26,727,940; 321 log 26,727,940
- science/fluid/transport/MixtureViscosity.java: 103 log 1,493,187; 127 exp 223,927
- science/fluid/transport/SlurryTransport.java: 27 pow 69,455
- science/material/ViscosityCorrelation.java: 95 exp 4,987,634; 95 log 14,962,902; 95 log 14,962,902; 95 log 14,962,902; 99 pow 3,790,876
- science/thermo/PengRobinson78.java: 126 log 9,187; 135 log 183,113; 194 cbrt 17,452; 194 cbrt 17,452; 203 acos 461; 206 cos 461; 207 cos 461; 208 cos 461
- science/thermo/PengRobinsonKernel.java: 271 log 26,727,940; 275 log 26,727,940; 339 log 31; 476 cbrt 22,237,506; 476 cbrt 22,237,506; 484 cbrt 162; 499 acos 15,609,187; 500 cos 15,609,187; 501 cos 15,609,187; 502 cos 15,609,187
Note: sites on the same line with the same function share one counter key (line:function), so their call count is printed once per site and is the combined count.

Which families each site feeds: the libm intrinsic functions (log, exp, pow, cos, log10) on every HotSpot build; `cbrt`
(the Peng-Robinson Cardano roots, `PengRobinsonKernel` 476/484, `PengRobinson78` 194/198: 22 million calls in the gates)
additionally on JDK 25+, which is family B's only libm difference from A; OpenJ9 differs from HotSpot at every
intrinsic site. Static reachability (jdeps) links far more of the column into the fluid packages (through
`MaterialCatalog` and `FluidCheckpointStore -> CreateChemE`), but none of those column sites run in the fluid gates.

Test-side calls: the chain fixture's pressures `150000 + 1000 cos(0.7 i)` (`SolverRegressionHarness`,
`FluidNetworkBenchmarkTest`, the probe) use `Math.cos`; 6 of the 100 cosines differ by 1 ulp between HotSpot and
fdlibm, none of the 100 pressures does (`src/CosChain.java`), so the fixture input is the same on every runtime; the
fixture now uses `DeterministicMath.cos` anyway. `TranslatedPengRobinsonDerivativesTest` built its random compositions
with `Math.pow`/`Math.exp`, so its printed maxima differed between runtimes; routed. Other test-side `Math` calls compute
expected values compared with a tolerance; they cannot change a product output and are left alone.

## 7. Diagnosis

### 7.1 Linux families

Every difference found in the sweep is explained by the libm:

1. Within a family, every output is byte-identical across 11 vendors, every tested 21.0.x update from GA to 21.0.12.1,
   and JIT/interpreter
   (the harness agent also found C1-only, C2-only, `-XX:-UseFMA`, `UseAVX=0`, `UseSSE=2` identical, and core counts
   1/2/16/32 via `-XX:ActiveProcessorCount` give the same junction lines).
2. HotSpot with `-XX:-UseLibmIntrinsic` (its `Math` then equals `StrictMath` on all 11 functions) produces OpenJ9's
   outputs byte for byte on all four probe files: the JVM, JIT, GC, identity hashes, class library build and thread
   scheduling of OpenJ9 contribute nothing.
3. B differs from A in exactly one `Math` function, `cbrt` (JDK 25's new intrinsic), and in no other probe of the sweep.

So the fluid solver has no other runtime-dependent input in these paths (no identity-hash iteration order, no thread
race, no time source reaching the state): once the libm is fixed, the outputs must agree. The after sweep confirms it
(section 10.1).

### 7.2 The magnitude

A 1-ulp change in a fugacity or a pipe friction factor enters the Newton residual; the converged state differs at the
convergence tolerance's roundoff, and the adaptive step control can accept or reject a different substep when an error
estimate sits near its threshold (PhasePortPriorityTest's slice counts, LevelHeadTest's accepted counts move by one or
two in the re-baseline). Nothing larger than that was seen in the fluid code.

### 7.3 The Windows JDK 21.0.11 difference (inferred)

The owner's Gradle run 156 (Windows, `C:/Program Files/Java/jdk-21.0.11`, sources identical) differs from this
container in one ledger value, `MIXED_GAS_TRANSIENT interval=0.1 key=0.02:5:false` (worstMoles 8.6165e-16 vs
9.9952e-16). What the sweep shows:

- It is not the JDK update: Temurin 21.0.11 on Linux gives the Linux value, as do all 22 family-A runtimes, including
  Oracle JDK 21.0.12.1 and Microsoft 21.0.12.1.
- It is not the Gradle lane: the Gradle gates of the phase-ports batch in this container give the Linux value
  (`tools/phase-ports-probes/wp6/logs/01-junction-lines-wp6.txt`).
- It is not the core count, the JIT tier, FMA or the AVX/SSE level (above).
- The value is libm-sensitive: every libm variant seen (JDK 25's `cbrt`, fdlibm) moves all 12 interval-0.1 transient
  lines, while the Windows run moves only one, so the Windows run differs in fewer call results than any Linux family.
- chain-100 on Windows matched the Linux reference exactly (it was recorded there and passed here at 0.000e+00).

Inference: on Windows x64 a small number of `Math` calls take a different machine path (a platform-dependent intrinsic
selection, or HotSpot's C fallback `SharedRuntime::d{log,exp,pow,...}`, compiled by MSVC on Windows and GCC on Linux),
rare enough to touch one of the 33 lines. This cannot be tested without Windows. It does not matter for the fix: after
the change no fluid result goes through `Math.log/exp/pow/cbrt/cos/log10/...` at all, only through `StrictMath`/the
facade, which are Java bytecode with strict IEEE semantics on every platform. Two checks the owner can run on Windows:
`java tools/jdk-determinism/src/MathSweep.java` (compare the `mathHash` column with `captures/before/system/`: the
differing function is named), and the fluid suite on the prototype (the 33 lines must equal
`tools/cloud-science-harness/reference/junction-lines.txt`).

### 7.4 The column today

The column solver (`science/column`) was outside the sweep's gates, so it was run separately on base code under three
runtimes (javac build of the 121 column/thermo/material test files, JUnit console; `ColumnInputPresetTest` fails
everywhere on the harness because it needs the real block entity, it passes in Gradle):

| runtime (base code) | column/thermo/material tests | failures besides ColumnInputPresetTest |
|---|---|---|
| HotSpot 21.0.10 | 562/563 | none |
| OpenJ9 21.0.10 | 557/563 | `V3ExactWarmStartSweepTest` x2, `V3LearnedRecoveryTest.anAcceptedLearnedCorrectionIsIdenticalUnderEitherRecoveryRule`, `V3SideDrawCalculatorTest.legalNearFeedDrawNeverBecomesInvalidInputWhenTheDrawBlindSeedIsAvailable`, `RegroupedCrudeTest` 45 s deadline (slower JIT) |
| HotSpot 25.0.4.1 | 559/563 | `V3ExactWarmStartSweepTest.numericalTwoPhaseMatrixRemainsRejectedAfterFreshExactInputHotStarts`, `V3RequestedRecoveryTest.coarseRecoveryNeverPublishesTheDrySurrogateForAnAuthoredFeatureRequest`, `V3SimultaneousColumnSolverTest.registeredPrBinaryNumericalTwoPhaseRootCannotPassTheIndependentPhaseGate` |

So today the column publishes a different outcome (success vs nonconvergence, accepted vs rejected state) depending on
the player's JDK, and its tests are tuned to HotSpot 21's libm (family A; 22-24 share it). Logs: `tools/jdk-determinism/logs/column-base/`.

## 8. The fix

### 8.1 Rule

Every elementary function of the fluid code that the JLS does not require to be correctly rounded goes through
`com.wormzjl.createcheme.science.math.DeterministicMath`, whose results are bitwise `StrictMath`'s (fdlibm 5.3):
`log, log10, log1p, exp, expm1, pow, cbrt, cos, sin, tan, acos, asin, atan, atan2, sinh, cosh, tanh, hypot`.
`+ - * /`, `Math.sqrt`, `Math.fma` and the exact `Math` functions (`abs, min, max, floor, ceil, rint, round, copySign,
signum, ulp, scalb, getExponent, clamp, nextUp, *Exact, floorMod`) stay: they are identical everywhere by specification
and by the sweep. `Math.random` is banned in the scanned code.

### 8.2 Facade, not direct `StrictMath` calls

Direct `StrictMath` calls would be deterministic too, but measured on the container:

| | Math (HotSpot intrinsic) | StrictMath | DeterministicMath |
|---|---|---|---|
| `pow` ns/call, HotSpot 21 / 25 / OpenJ9 21 / GraalVM CE 25 | 12.1 / 12.9 / 63.1 / 11.8 | 91.2 / 90.8 / 61.7 / 58.8 | 42.2 / 46.3 / 51.1 / 51.1 |
| `pow` bytes allocated per call (HotSpot 21 and 25; OpenJ9) | 0 | 96; 72 | 0 |
| `log`, `exp` ns/call HotSpot 21 | 6.5, 6.5 | 8.2, 9.5 | = StrictMath |
| `cos` bytes per call (argument reduction beyond pi/4) | 0 | 32; 56 | = StrictMath |

(`logs/10-mathsweep-bench.txt`: warmed loop with a volatile black hole, best of 30 rounds, indicative, not JMH.)
`java.lang.FdLibm.Pow` of JDK 21 to 27 declares its constant tables `BP`, `DP_H`, `DP_L` as local array initialisers
inside `compute`, so every call allocates three arrays that escape analysis does not remove. `FdlibmPow` is a
transliteration of the original fdlibm `e_pow.c` (netlib, Sun's permissive notice, compatible with the mod's MIT
licence; no OpenJDK source copied) with the tables hoisted to static finals. Every `double` operation is the C source's
in the same order, and Java evaluates each with IEEE round-to-nearest, so the result is the fdlibm result:
`tools/jdk-determinism/src/PowEquivalence.java` compared it with `StrictMath.pow` on 300 million inputs per runtime
(random bit patterns, negative bases with integer and half-integer exponents, bases within 2^-19 of 1 with huge
exponents, results at the overflow and underflow boundaries including 889,640 subnormal results per 2 million, small
integer exponents, the thermodynamic ranges) on HotSpot 21 and 25, OpenJ9 21, GraalVM CE 25 and Oracle GraalVM 21, and
6 million under `-Xint`: 0 differences (`logs/11-pow-equivalence.txt`). `DeterministicMathTest` (in `fluidScienceTest`)
repeats a short form (37 x 37 special values, 2 million sampled inputs) and asserts that 200,000 calls allocate nothing.

The facade also gives the rule one name the guard can enforce, and one place to change an implementation later (a
faster `pow`, or a correctly rounded library) without touching the 91 call sites.

### 8.3 Guard

`DeterministicMathSourceTest` (package `science.fluid`, so `fluidScienceTest` and `test` run it) scans
`src/main/java/.../science/**`, `.../runtime/**` and the exact-regression fixtures `src/test/java/.../fluid/benchmark/**`
(comments stripped) and fails on any call, method reference or static import of a banned `Math` or `StrictMath`
function outside `DeterministicMath.java`. Exempt pending decision 12.2: `science/column/**` except
`V3WaterProperties.java`. It asserts that the roots and the shared file exist and that at least 100 files were scanned
(106 today), and a second method checks the scanner on positive and negative snippets. Before the routing it reported
the 93 expected sites; after, none.

### 8.4 Alternatives considered

| alternative | verdict |
|---|---|
| `-XX:-UseLibmIntrinsic` | HotSpot diagnostic flag (needs `-XX:+UnlockDiagnosticVMOptions`); makes HotSpot C2 builds agree with OpenJ9, but not GraalVM (the Graal JIT keeps its intrinsics and becomes tier-dependent, section 5), not a guarantee for any future intrinsic, and a mod cannot set JVM flags for the player's launcher or server host. Useful only as the diagnostic it was here. |
| `-XX:+UseStrictMath` | Does not exist in HotSpot, OpenJ9 or GraalVM. |
| `strictfp` | The default since Java 17 (JEP 306) for `+ - * /`; it never covered `Math`'s library functions. |
| A bundled math library | A Java fdlibm port is what `StrictMath` already is; the facade bundles exactly one such function (`pow`) for speed. A correctly rounded library (for example a Java port of CORE-MATH) would also be deterministic and more accurate, but is thousands of lines to import and verify; the facade allows it later. |
| Bundle or require one JDK | Not the mod's call: players run the launcher's bundled Microsoft JDK, Prism/MultiMC with Temurin, Zulu or GraalVM, servers any vendor, macOS on aarch64. |
| Rely on the solver's tolerances | No: the owner requires bitwise results, and the equation gate, the island certificates, the checkpoint replay and chain-100 exact compare doubles; section 4 shows the tolerances hide differences that are there, and section 7.4 shows they already change outcomes in the column. |
| Fix-point or decimal arithmetic | Out of proportion; the double arithmetic is already deterministic, only the libm is not. |

## 9. Scope boundary

Routed in the prototype: every non-correctly-rounded call in `science/fluid/**`, `science/thermo/**`,
`science/material/**` and `science/column/v3/thermo/V3WaterProperties.java` (91 sites in 13 files); `runtime/**` has
none. This is the set of files whose sites execute in the fluid gates (section 6), plus the whole of their packages so
the guard's rule is a package rule. `V3WaterProperties` belongs to the column package but is the water model of
`FluidThermodynamics` and `WaterRegion1` (5 sites, 5.4 million calls in the gates): it must be routed for the fluid
results to be deterministic, and because the column uses it too, routing it changes the column (section 10.6).

Not routed: the other 17 column files (81 sites), pending decision 12.2.

## 10. Prototype: before and after

### 10.1 Across runtimes

| output | before: distinct results over the 36 runtimes | after |
|---|---|---|
| 33 junction lines | 3 (A 22, B 11, C 3) | **1** (all 36) |
| chain-100 exact against the checked-in reference | 0 on A; 8.0e-15 on B; 5.7e-15 on C | **0.000e+00 on all 36** (against the re-captured reference) |
| BitwiseProbe, 4 files | 3 | **1** |
| BitwiseProbe with `-XX:-UseLibmIntrinsic` (33 non-OpenJ9 runtimes) | 5 (C and one group per GraalVM build) | **1**, identical to the default runs |
| `fluidRuntimeTest` (harness) | 249/249 everywhere | 249/249 everywhere |
| MathSweep | the `Math` columns are properties of the JDK and unchanged; the fluid code no longer calls them | |

Every runtime's capture: `tools/jdk-determinism/captures/after/<id>/`, comparison `captures/after/comparison.md`; the
single chain-100 output is `captures/probe-variants-after/A-all-36/chain-100.json`, byte-identical to the new
`src/test/resources/fluid/regression/chain-100.json`, and the single set of junction lines is the new
`tools/cloud-science-harness/reference/junction-lines.txt` (`harness.sh runtime` compares with it again).

### 10.2 Cost

`harness.sh runtime` on the container JDK (OpenJDK 21.0.10, 4 cores, machine otherwise idle), runs alternated with the
base build (`logs/09-timing-harness-runtime.txt`):

| build | wall per run (s) | mean | `MIXED_GAS_COST` ms | mean | Newton solves | allocatedMB |
|---|---|---|---|---|---|---|
| base `efd123a` | 55.0, 54.2, 54.0, 53.3, 57.4, 54.6 | 54.8 | 433, 422, 494, 446, 476, 423 | 449 | 286 | 135.4 |
| direct `StrictMath` (first prototype) | 72.3, 72.8, 73.0 | 72.7 (+33 %) | 819, 877, 863 | 853 (+90 %) | 286 | 574 |
| direct `StrictMath` except `pow` (attribution only) | 55.9, 55.3 | 55.6 | 446, 513 | 480 | 286 | 137.5 |
| **DeterministicMath (the prototype)** | 64.6, 65.7, 62.3 | **64.2 (+17 %)** | 637, 679, 650 | **655 (+46 %)** | 286 | 137.7 |
| DeterministicMath + pow strength reduction (option, not committed) | 54.1, 52.3, 55.0 | 53.8 | 439, 452, 445 | 445 | 286 | 137.7 |

The attribution row shows that `pow` is the whole cost; `log`/`exp` at 1.2-1.5x per call do not show. The strength
reduction (`pow-strength-reduction.patch`, 10 changed lines) replaces `pow(d, 4)`, `pow(logarithm, 3)`,
`pow(re, 1.9)` and `log(10)` in `PipeResistance` and the two `pow(x, 3)` of `TranslatedPengRobinson` by
multiplications and a reuse of `pow(re, 0.9)`; it is deterministic but changes those values at the ulp level again, so it
belongs in the same re-baseline if taken (decision 12.4). Gradle full `test` wall: 5 min 36 s / 6 min 41 s base, 4 min
54 s prototype (noise dominates).

### 10.3 Re-baselined values

**Junction lines** (`tools/cloud-science-harness/reference/junction-lines.txt` re-recorded on the prototype):

| line | before (HotSpot 21 libm intrinsics) | after (DeterministicMath = fdlibm) |
|---|---|---|
| LIQUID_JUNCTION interval=0.1 tanks:0.02 | Q(1s)=3.103e-12 kg/s m_J/Q=5.040e+11 s tauFit(1.0-1.1s)=1.003e+12 s mismatch(1s)=-0.983 mismatch(end)=NaN | Q(1s)=5.000e-12 kg/s m_J/Q=3.128e+11 s tauFit(1.0-1.1s)=7.383e+12 s mismatch(1s)=-0.983 mismatch(end)=0.0167 |
| LIQUID_JUNCTION interval=0.1 tanks:0.05 | Q(1s)=2.740e-11 kg/s m_J/Q=3.567e+11 s tauFit(1.0-1.1s)=Infinity s mismatch(1s)=-0.427 mismatch(end)=-0.427 | Q(1s)=1.562e-11 kg/s m_J/Q=6.258e+11 s tauFit(1.0-1.1s)=Infinity s mismatch(1s)=0.00294 mismatch(end)=0.00294 |
| LIQUID_JUNCTION interval=5 tanks:0.02 | Q(1s)=0.01503 kg/s m_J/Q=104.0 s mismatch(1s)=-0.342 mismatch(end)=-0.0806 perSlice=[-0.342,-0.0806,-0.0806,-0.0806] newtonSolves=17 | Q(1s)=0.01503 kg/s m_J/Q=104.0 s mismatch(1s)=-0.342 mismatch(end)=-0.894 perSlice=[-0.342,-0.894,-0.894,-0.894] newtonSolves=17 |
| LIQUID_JUNCTION interval=5 tanks:0.05 | Q(1s)=0.01503 kg/s m_J/Q=650.2 s mismatch(1s)=-0.356 mismatch(end)=-0.472 perSlice=[-0.356,-0.472,-0.472,-0.472] newtonSolves=17 | Q(1s)=0.01503 kg/s m_J/Q=650.2 s mismatch(1s)=-0.356 mismatch(end)=0.00295 perSlice=[-0.356,0.00295,0.00295,0.00295] newtonSolves=17 |
| MIXED_GAS_TRANSIENT interval=0.1 0.02:4:false | moles 5.514587206174876E-16, energy 1.2785353309169641E-15, solves 0 | moles 7.927219108876385E-16, energy 7.264405289300933E-16, solves 0 |
| MIXED_GAS_TRANSIENT interval=0.1 0.02:4:true | moles 1.2013212947883937E-15, energy 2.9155899314157096E-15, solves 0 | moles 1.3396337618739343E-15, energy 2.2158483478759394E-15, solves 0 |
| MIXED_GAS_TRANSIENT interval=0.1 0.02:5:false | moles 9.995189311191963E-16, energy 2.063091102161465E-15, solves 0 | moles 9.305865910420103E-16, energy 2.3246096925762984E-15, solves 0 |
| MIXED_GAS_TRANSIENT interval=0.1 0.02:5:true | moles 7.556908400314501E-16, energy 2.886434032101552E-15, solves 0 | moles 1.6144304309762797E-15, energy 2.2158483478759394E-15, solves 0 |
| MIXED_GAS_TRANSIENT interval=0.1 0.02:6:false | moles 9.61788341858209E-16, energy 8.71728634716112E-16, solves 0 | moles 1.2022354273227615E-15, energy 7.55498150087297E-16, solves 0 |
| MIXED_GAS_TRANSIENT interval=0.1 0.02:6:true | moles 8.915014561666219E-16, energy 2.769810434844924E-15, solves 0 | moles 7.61982401113646E-16, energy 2.186692448561782E-15, solves 0 |
| MIXED_GAS_TRANSIENT interval=0.1 0.05:4:false | moles 5.10099316571176E-15, energy 5.4395265571948675E-15, solves 0 | moles 5.204391675827539E-15, energy 5.761218987996714E-15, solves 0 |
| MIXED_GAS_TRANSIENT interval=0.1 0.05:4:true | moles 1.8286551733222133E-15, energy 4.607488907237493E-15, solves 0 | moles 1.752482580548483E-15, energy 4.991446316173951E-15, solves 0 |
| MIXED_GAS_TRANSIENT interval=0.1 0.05:5:false | moles 3.0662160794169303E-15, energy 5.731974221560183E-15, solves 0 | moles 1.654376161852463E-15, energy 4.0942673011144166E-15, solves 0 |
| MIXED_GAS_TRANSIENT interval=0.1 0.05:5:true | moles 1.2841621953470297E-15, energy 1.8311814887738755E-15, solves 0 | moles 9.880029589232332E-16, energy 1.5062944504430265E-15, solves 0 |
| MIXED_GAS_TRANSIENT interval=0.1 0.05:6:false | moles 1.3627627019630802E-15, energy 7.603639273498202E-16, solves 0 | moles 9.19864823825079E-16, energy 1.9009098183745505E-15, solves 0 |
| MIXED_GAS_TRANSIENT interval=0.1 0.05:6:true | moles 7.835885536287712E-16, energy 2.864912974372031E-15, solves 0 | moles 6.473122834324631E-16, energy 2.4218851948299643E-15, solves 0 |
| MIXED_GAS_TRANSIENT interval=5 0.02:4:false | moles 2.060975018267591E-16, energy 2.324609692576299E-16, solves 16 | moles 2.060975018267591E-16, energy 4.939795596724634E-16, solves 16 |
| MIXED_GAS_TRANSIENT interval=5 0.02:4:true | moles 3.2632104455903524E-16, energy 7.288974828539274E-16, solves 16 | moles 4.465445872913114E-16, energy 2.3324719451325675E-16, solves 16 |
| MIXED_GAS_TRANSIENT interval=5 0.02:5:false | moles 4.825263805403017E-16, energy 6.392676654584821E-16, solves 20 | moles 2.4044708546455227E-16, energy 3.1963383272924104E-16, solves 20 |
| MIXED_GAS_TRANSIENT interval=5 0.02:5:true | moles 4.121950036535182E-16, energy 4.081825903981993E-16, solves 17 | moles 2.4044708546455227E-16, energy 2.9155899314157094E-16, solves 17 |
| MIXED_GAS_TRANSIENT interval=5 0.02:6:false | moles 4.1359404046311573E-16, energy 4.35864317358056E-16, solves 20 | moles 4.808941709291045E-16, energy 1.7434572694322239E-16, solves 20 |
| MIXED_GAS_TRANSIENT interval=5 0.02:6:true | moles 2.7479666910234547E-16, energy 3.2071489245572804E-16, solves 19 | moles 5.839429218424842E-16, energy 3.2071489245572804E-16, solves 19 |
| MIXED_GAS_TRANSIENT interval=5 0.05:4:false | moles 2.4126319027015086E-16, energy 3.21692430801847E-16, solves 28 | moles 3.446617003859298E-16, energy 2.339581314922524E-16, solves 28 |
| MIXED_GAS_TRANSIENT interval=5 0.05:4:true | moles 5.968135467490589E-16, energy 5.611685207532844E-16, solves 26 | moles 3.4136606391190264E-16, energy 2.9535185302804443E-16, solves 26 |
| MIXED_GAS_TRANSIENT interval=5 0.05:5:false | moles 2.72552540392616E-16, energy 2.924476643653155E-16, solves 33 | moles 3.101955303473368E-16, energy 4.679162629845048E-16, solves 33 |
| MIXED_GAS_TRANSIENT interval=5 0.05:5:true | moles 5.780909902760545E-16, energy 1.7721111181682666E-16, solves 27 | moles 2.7057941204136535E-16, energy 3.544222236336533E-16, solves 27 |
| MIXED_GAS_TRANSIENT interval=5 0.05:6:false | moles 2.38483472843539E-16, energy 2.339581314922524E-16, solves 35 | moles 4.76966945687078E-16, energy 2.924476643653155E-16, solves 35 |
| MIXED_GAS_TRANSIENT interval=5 0.05:6:true | moles 1.6350177741836058E-16, energy 5.611685207532844E-16, solves 29 | moles 4.76966945687078E-16, energy 4.725629648448711E-16, solves 29 |

28 of 33 lines differ; the other 5 (the four LIQUID_JUNCTION generator lines and MIXED_GAS_COST newtonSolves=286) are unchanged.

**chain-100** (`src/test/resources/fluid/regression/chain-100.json`, re-captured with
`gradlew fluidSolverRegression -PfluidRegressionCapture=true` per `FLUID_SOLVER_REGRESSION_RERECORD.md`, then
`-PfluidRegressionMode=exact` at 0.000e+00): sha256 `7cb85a9b...` -> `fa2b9e26...`; 2,719 of 3,003 numbers change;
max deviation old -> new: moles 5.657e-15 relative (node 72 moles[16] 5.181434663998052 -> 5.181434663998082),
temperature 3.411e-13 K (node 23 350.04130561159695 -> 350.0413056115973), phase fraction 3.331e-16, flow 1.841e-11
relative (pipe 13 -0.0014639460202492145 -> -0.001463946020222265 kg/s, 2.7e-14 kg/s against a controller allowance of
3.2e-8 kg/s). Same 3 accepted / 0 rejected steps (1.0, 2.0, 2.0 s), 4 Newton solves, 29 iterations, 33 residual
evaluations, 4 Jacobians; the step error estimates agree to 12 significant digits (0.0012837802542459177 ->
0.0012837802542453572). The new file is byte-identical to the chain-100 OpenJ9 produced on the base code, and the capture is
byte-identical between the direct-`StrictMath` and the facade builds.

**Assertions:** none changed. No tolerance changed.

### 10.4 Moved test outputs (Gradle, base vs prototype)

`captures/gradle-stdout-base-vs-prototype.md` classifies the printed output of every test class (Gradle XML
`system-out`), ignoring the line positions that already differ between two runs of the base (wall times, identity
hashes, HashMap order of printed `reasons={...}` maps). 20 fluid classes and 7 column classes move; the test verdicts
move only in the column (10.6). Fluid classes: printed states and ledgers differ at 1e-16 to 1e-8 relative
(`PhysicalFluidTopologyTest` 6.7e-4 on a -2.2e-8 residual, `LevelHeadTest` 1.2e-3 on a level after a different
accepted-step count), accepted/rejected substep counts move by 1-2 in `PhasePortPriorityTest` (12 counts over 80
slices), `LevelHeadTest` (6), `IslandCertificateTest`, `InletPhaseTest`, `FluidPumpedFillLineTest` (1 each), and the
two roundoff ratios of section 4.2 (`LIQUID_JUNCTION` tank lines). All assertions pass with unchanged tolerances.

### 10.5 Classification of the fluid moves

Expected re-baseline, not a defect: the harness science (225) and runtime (249) suites of the prototype on HotSpot print,
after masking wall times and identity hashes, exactly what the base code prints on OpenJ9 (`logs/12-*`; the only
difference is the new tests' count). Every moved fluid number is therefore one a player on OpenJ9 already got from the
base code; the change picks OpenJ9's libm for everyone.

### 10.6 The column under the prototype

With only `V3WaterProperties` routed, the column mixes fdlibm water properties with HotSpot-intrinsic hydrocarbon
thermodynamics, a combination no runtime had before. Gradle full `test`: 1169/1171, the 2 failures are column tests that
pass on base code on all three runtimes tried:

- `V3LearnedRecoveryTest.theRampHandoffRecoversACappedCorrectionOnAnAuthoredDrawColumn`: expected `Success`, got
  `Failure` (the learned-recovery column no longer converges);
- `V3NearbyInputRecoveryTest.nearby551KelvinColdInputPassesTheSameFreshAcceptanceGate`: `NONCONVERGENCE`, iteration
  budget of 128 exhausted at scaled residual 3.9e-6 (every acceptance check passes except convergence).

Their printed siblings move the same way (`V3ColdStartSweepTest` `feed_k_545` SUCCESS in 18 iterations ->
NONCONVERGENCE at 128; `V3ThirtyStageColdStartTest` a different failure code). These are column solves sitting on a
convergence cliff that a 1-ulp change of the water properties pushes over: the same fragility section 7.4 shows on
OpenJ9 and JDK 25 today. Not a fluid defect; recorded for decision 12.2.

**Whole-science variant** (`full-science-routing.patch` on top of the prototype: the 17 other column files and the
test's frozen flash reference `V3FeedFlashReference` through the facade, guard exemption removed; Gradle full `test` on
a scratch copy, `logs/07-*`): 1167/1171; the 4 failures are exactly the 4 column tests OpenJ9 fails on base code
(`V3ExactWarmStartSweepTest` x2, `V3LearnedRecoveryTest.anAcceptedLearnedCorrectionIsIdenticalUnderEitherRecoveryRule`,
`V3SideDrawCalculatorTest.legalNearFeedDrawNeverBecomesInvalidInputWhenTheDrawBlindSeedIsAvailable`); the two
prototype failures above pass again. `V3FeedFlashEquivalenceTest` compares the column flash with a frozen copy in the
test tree and passes once that copy uses the facade too. With the whole science routed the column is deterministic, and
its 4 JDK-sensitive tests need column work (budgets or fixtures chosen on fdlibm), which this batch does not do because
it would change assertions. (The first run of this variant, on the direct-`StrictMath` prototype before the frozen reference was routed, failed
7: the same 4, the 2 `V3FeedFlashEquivalenceTest` methods, and `RegroupedCrudeTest`'s 45 s wall-clock deadline, which
passed in the facade run; its log is `logs/strictmath-direct/08-*` for the equivalence re-run.)

## 11. Gates

Gradle, one invocation at a time, nothing else running, `bash ./gradlew` (the worktree's `gradlew` is not executable),
logs in `tools/jdk-determinism/logs/`:

| gate | base `efd123a` | prototype |
|---|---|---|
| fluid suites `test --tests science.fluid.* --tests runtime.fluid.*` | 472 (phase-ports close) | **477/477** (472 + 2 `DeterministicMathSourceTest` + 3 `DeterministicMathTest`), `01-*` |
| `fluidSolverRegression -PfluidRegressionCapture=true` | - | captured, `02-*` (byte-identical to the direct-`StrictMath` capture) |
| `fluidSolverRegression -PfluidRegressionMode=exact` | 0.000e+00 | **0.000e+00** after the re-capture, `03-*` |
| adjacent (7 classes) | 45/45 | **45/45**, `04-*` |
| `compileFluidGameTestJava compileMcpCompatJava` | green | **green**, `05-*` |
| full `test` | 1166/1166 twice (5 min 36 s, 6 min 41 s; `RegroupedCrudeTest` met its 45 s deadline both times on the idle machine) | **1169/1171**: the 2 column failures of 10.6, `06-*` |
| harness `all` stand-in, container JDK | science 223, runtime 249, adjacent 45, chain-100 0 | science **228/228** (223 + 5 new), runtime **249/249** with the 33 lines identical to the re-recorded reference, adjacent **45/45**, chain-100 **0.000e+00** (`13-*`) |

The same gates on the first (direct `StrictMath`) prototype are in `logs/strictmath-direct/` (474 fluid, 1166/1168 with the
same 2 column failures, outputs bitwise equal to the facade's).

## 12. Decision points for the owner

1. **Adopt the fix** (DeterministicMath facade + guard + one-time re-baseline of the 28 junction lines and chain-100;
   no fluid assertion changes). Recommended: it is the only route that guarantees identical results on every JVM,
   including ones not tested.
2. **Column scope.** (a) Route the whole `science/` tree (`full-science-routing.patch`): the column becomes
   deterministic; 4 column tests that already fail on OpenJ9 today fail everywhere until they are re-tuned on fdlibm
   (a column follow-up that changes assertions and needs the owner's approval). (b) Fluid boundary only (the prototype):
   the column stays JDK-dependent and 2 column tests fail everywhere until re-tuned. (c) Keep `V3WaterProperties` on
   `Math`: the fluid results stay JDK-dependent through the water model; not recommended. Recommended: (a), with the
   4 column tests handled in a column batch.
3. **Accept the re-baseline** (values in 10.3): all moves are ulp-level libm effects and equal OpenJ9's current
   outputs.
4. **Cost.** Accept +17 % wall / +46 % `MIXED_GAS_COST`, or take the `pow` strength reduction in the same re-baseline
   (cost back to base; changes a few more values at the ulp level). A faster deterministic `pow` is also possible later
   behind the facade.
5. **Test-tree reach of the guard.** It scans the main fluid code and the regression fixtures; extending it to every
   fluid test (expected values computed with `Math` and compared with tolerances) is possible but not needed for
   product determinism.

## 13. What the sweep cannot prove, and why the fix closes it

Not measured: Windows and macOS runtimes (the owner's Windows line is only inferred, 7.3), aarch64 (Apple Silicon,
ARM servers: HotSpot's aarch64 intrinsic set and C fallbacks are different code), other OpenJ9 releases, future JDKs
(JDK 25 added a `cbrt` intrinsic; later releases may add `tanh`-style intrinsics for more functions), and JVMs not
tried (Android ART, other vendors' JITs).

Why `StrictMath`/`DeterministicMath` closes the gap by specification rather than by sampling: the `StrictMath`
Javadoc requires the fdlibm results bit for bit on every conforming Java platform; since JDK 21 those methods are Java
bytecode (no native code, checked with javap), and since Java 17 every `double` operation in Java code is strict IEEE 754
(JEP 306), so no JIT, CPU or OS may change a bit of them. `FdlibmPow` is ordinary Java code under the same rule,
verified equal to `StrictMath.pow` on 1.5 billion inputs. `+ - * /`, `Math.sqrt` and `Math.fma` are correctly rounded by
the JLS. What remains outside this argument is not the libm: JVM bugs, and nondeterminism the sweep saw no trace of in
the fluid paths (iteration order of identity-hashed maps, thread races, wall-clock inputs). The sweep's evidence for
the latter is section 7.1 item 2: OpenJ9 (different identity hashes, JIT, GC and scheduling) matched HotSpot bit for bit
once the libm matched.

## 14. Files

- Tool folder: `tools/jdk-determinism/` (README): `fetch-jdks.sh`, `sweep.sh`, `compare.py`, `xmlcases.py`,
  `stdoutdiff.py`, `src/MathSweep.java`, `src/BitwiseProbe.java` (the d9 probe with the facade-equivalent `StrictMath.cos`
  fixture), `src/PowEquivalence.java`, `src/CosChain.java`, `site-counts/`, `captures/before/`, `captures/after/`,
  `captures/probe-variants-*/`, `captures/tier/`, `captures/gradle/`, `logs/`, `full-science-routing.patch`,
  `pow-strength-reduction.patch`.
- Harness: `tools/cloud-science-harness/harness.sh` (`JAVA=` override), `reference/junction-lines.txt` re-recorded.
- Code (WIP commit): `science/math/DeterministicMath.java`, `science/math/FdlibmPow.java`, the 13 routed files,
  `DeterministicMathSourceTest`, `DeterministicMathTest`, the three test fixtures, `chain-100.json`.

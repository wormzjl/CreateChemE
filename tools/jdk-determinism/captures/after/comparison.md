# Sweep comparison: $SP/jdk-sweep/cap-after

Reference runtime: `system` (21.0.10+7-Ubuntu-124.04).

## Runtimes and summary

| id | java.runtime.version | VM | runtime tests | junction lines | chain-100 exact | probe (4 files) | probe -UseLibmIntrinsic |
|---|---|---|---|---|---|---|---|
| system | 21.0.10+7-Ubuntu-124.04 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| corretto-21 | 21.0.12.1+12-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| corretto-25 | 25.0.4.1+10-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| dragonwell-21 | 21.0.12.0.12 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| graalvm-ce-21 | 21.0.2+13-jvmci-23.1-b30 | HotSpot + Graal JIT | 249/0 ok/failed | A | 0.000e+00 | A | A |
| graalvm-ce-25 | 25.0.4.1+1-jvmci-25.3-b22 | HotSpot + Graal JIT | 249/0 ok/failed | A | 0.000e+00 | A | A |
| kona-21 | 21.0.12+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| liberica-21 | 21.0.12.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| liberica-25 | 25.0.4.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| microsoft-21 | 21.0.12.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| microsoft-25 | 25.0.4.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| oracle-graalvm-21 | 21.0.12+7-LTS-jvmci-23.1-b96 | HotSpot (Oracle) + Graal JIT | 249/0 ok/failed | A | 0.000e+00 | A | A |
| oracle-graalvm-25 | 25.0.4+7-LTS-jvmci-b01 | HotSpot (Oracle) + Graal JIT | 249/0 ok/failed | A | 0.000e+00 | A | A |
| oracle-jdk-21 | 21.0.12.1+1-LTS-4 | HotSpot (Oracle) | 249/0 ok/failed | A | 0.000e+00 | A | A |
| oracle-jdk-25 | 25.0.4.1+1-LTS-5 | HotSpot (Oracle) | 249/0 ok/failed | A | 0.000e+00 | A | A |
| oracle-openjdk-21 | 21+35-2513 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| oracle-openjdk-21.0.2 | 21.0.2+13-58 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| oracle-openjdk-25 | 25.0.2+10-69 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| sapmachine-21 | 21.0.12.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| semeru-21.0.10 | 21.0.10+7-LTS | OpenJ9 | 249/0 ok/failed | A | 0.000e+00 | A | n/a |
| semeru-21.0.5 | 21.0.5+11-LTS | OpenJ9 | 249/0 ok/failed | A | 0.000e+00 | A | n/a |
| semeru-25.0.2 | 25.0.2+10-LTS | OpenJ9 | 249/0 ok/failed | A | 0.000e+00 | A | n/a |
| temurin-21-latest | 21.0.12.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| temurin-21.0.10 | 21.0.10+7-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| temurin-21.0.11 | 21.0.11+10-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| temurin-21.0.2 | 21.0.2+13-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| temurin-21.0.5 | 21.0.5+11-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| temurin-21.0.8 | 21.0.8+9-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| temurin-22 | 22.0.2+9 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| temurin-23 | 23.0.2+7 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| temurin-24 | 24.0.2+12 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| temurin-25 | 25.0.4.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| temurin-26 | 26.0.2.1+1 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| temurin-27 | 27+35 | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| zulu-21 | 21.0.12.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |
| zulu-25 | 25.0.4.1+1-LTS | HotSpot | 249/0 ok/failed | A | 0.000e+00 | A | A |

Letters name groups of byte-identical output (A = the reference's group). chain-100 exact = the maximum relative moles deviation of `harness.sh regression` against the checked-in `chain-100.json`.

## Junction lines by group

- group A (36): system, corretto-21, corretto-25, dragonwell-21, graalvm-ce-21, graalvm-ce-25, kona-21, liberica-21, liberica-25, microsoft-21, microsoft-25, oracle-graalvm-21, oracle-graalvm-25, oracle-jdk-21, oracle-jdk-25, oracle-openjdk-21, oracle-openjdk-21.0.2, oracle-openjdk-25, sapmachine-21, semeru-21.0.10, semeru-21.0.5, semeru-25.0.2, temurin-21-latest, temurin-21.0.10, temurin-21.0.11, temurin-21.0.2, temurin-21.0.5, temurin-21.0.8, temurin-22, temurin-23, temurin-24, temurin-25, temurin-26, temurin-27, zulu-21, zulu-25; 0 of 33 lines differ from the reference

## BitwiseProbe groups: differing values against the reference (text files print one hex double per line; chain-100.json counts numbers)

| group | runtimes | chain-100.json | scenarios.txt | gas-ports.txt | liquid-ports.txt |
|---|---|---|---|---|---|
| A | system, corretto-21, corretto-25, dragonwell-21, graalvm-ce-21, graalvm-ce-25, kona-21, liberica-21, liberica-25, microsoft-21, microsoft-25, oracle-graalvm-21, oracle-graalvm-25, oracle-jdk-21, oracle-jdk-25, oracle-openjdk-21, oracle-openjdk-21.0.2, oracle-openjdk-25, sapmachine-21, semeru-21.0.10, semeru-21.0.5, semeru-25.0.2, temurin-21-latest, temurin-21.0.10, temurin-21.0.11, temurin-21.0.2, temurin-21.0.5, temurin-21.0.8, temurin-22, temurin-23, temurin-24, temurin-25, temurin-26, temurin-27, zulu-21, zulu-25, system (-UseLibmIntrinsic), corretto-21 (-UseLibmIntrinsic), corretto-25 (-UseLibmIntrinsic), dragonwell-21 (-UseLibmIntrinsic), graalvm-ce-21 (-UseLibmIntrinsic), graalvm-ce-25 (-UseLibmIntrinsic), kona-21 (-UseLibmIntrinsic), liberica-21 (-UseLibmIntrinsic), liberica-25 (-UseLibmIntrinsic), microsoft-21 (-UseLibmIntrinsic), microsoft-25 (-UseLibmIntrinsic), oracle-graalvm-21 (-UseLibmIntrinsic), oracle-graalvm-25 (-UseLibmIntrinsic), oracle-jdk-21 (-UseLibmIntrinsic), oracle-jdk-25 (-UseLibmIntrinsic), oracle-openjdk-21 (-UseLibmIntrinsic), oracle-openjdk-21.0.2 (-UseLibmIntrinsic), oracle-openjdk-25 (-UseLibmIntrinsic), sapmachine-21 (-UseLibmIntrinsic), temurin-21-latest (-UseLibmIntrinsic), temurin-21.0.10 (-UseLibmIntrinsic), temurin-21.0.11 (-UseLibmIntrinsic), temurin-21.0.2 (-UseLibmIntrinsic), temurin-21.0.5 (-UseLibmIntrinsic), temurin-21.0.8 (-UseLibmIntrinsic), temurin-22 (-UseLibmIntrinsic), temurin-23 (-UseLibmIntrinsic), temurin-24 (-UseLibmIntrinsic), temurin-25 (-UseLibmIntrinsic), temurin-26 (-UseLibmIntrinsic), temurin-27 (-UseLibmIntrinsic), zulu-21 (-UseLibmIntrinsic), zulu-25 (-UseLibmIntrinsic) | 0 / 3003 numbers | 0 / 3460 lines | 0 / 997 lines | 0 / 608 lines |

## MathSweep: Math vs StrictMath per function (default JIT, last of 3 repetitions)

StrictMath hashes: 11 distinct (function, hash) pairs over all runtimes and modes (= 11 means StrictMath is identical everywhere); input hashes: 11 distinct pairs.

Cell = differing inputs out of 1,048,576 / max ulp / Math-hash class (S = identical to StrictMath; digits name other distinct Math output sets). Columns: default JIT; `-Xint`; `-XX:-UseLibmIntrinsic`.

### mode jit

| runtime | log | exp | pow | cbrt | log1p | expm1 | cos | log10 | acos | sqrt | fma |
|---|---|---|---|---|---|---|---|---|---|---|---|
| system | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| corretto-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| corretto-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| dragonwell-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| graalvm-ce-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| graalvm-ce-25 | 28115/1/1 | 80844/1/1 | 87715/1/2 UNSTABLE | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| kona-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| liberica-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| liberica-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| microsoft-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| microsoft-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-graalvm-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-graalvm-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-jdk-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-jdk-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-openjdk-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-openjdk-21.0.2 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-openjdk-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| sapmachine-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| semeru-21.0.10 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| semeru-21.0.5 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| semeru-25.0.2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temurin-21-latest | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-21.0.10 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-21.0.11 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-21.0.2 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-21.0.5 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-21.0.8 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-22 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-23 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-24 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-26 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-27 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| zulu-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| zulu-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |

### mode xint

| runtime | log | exp | pow | cbrt | log1p | expm1 | cos | log10 | acos | sqrt | fma |
|---|---|---|---|---|---|---|---|---|---|---|---|
| system | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| corretto-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| corretto-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| dragonwell-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| graalvm-ce-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| graalvm-ce-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| kona-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| liberica-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| liberica-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| microsoft-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| microsoft-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-graalvm-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-graalvm-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-jdk-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-jdk-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-openjdk-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-openjdk-21.0.2 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| oracle-openjdk-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| sapmachine-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| semeru-21.0.10 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| semeru-21.0.5 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| semeru-25.0.2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temurin-21-latest | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-21.0.10 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-21.0.11 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-21.0.2 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-21.0.5 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-21.0.8 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-22 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-23 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-24 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-26 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| temurin-27 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| zulu-21 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 0 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |
| zulu-25 | 28115/1/1 | 80844/1/1 | 87714/1/1 | 87287/1/1 | 0 | 0 | 30675/1/1 | 69214/2/1 | 0 | 0 | 0 |

### mode nolibm

| runtime | log | exp | pow | cbrt | log1p | expm1 | cos | log10 | acos | sqrt | fma |
|---|---|---|---|---|---|---|---|---|---|---|---|
| system | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| corretto-21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| corretto-25 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| dragonwell-21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| graalvm-ce-21 | 28115/1/1 UNSTABLE | 80844/1/1 UNSTABLE | 87714/1/1 UNSTABLE | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| graalvm-ce-25 | 28115/1/1 UNSTABLE | 80844/1/1 UNSTABLE | 87048/1/3 UNSTABLE | 0 | 0 | 0 | 30675/1/1 UNSTABLE | 69214/2/1 UNSTABLE | 0 | 0 | 0 |
| kona-21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| liberica-21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| liberica-25 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| microsoft-21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| microsoft-25 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| oracle-graalvm-21 | 28115/1/1 UNSTABLE | 80844/1/1 UNSTABLE | 87714/1/1 UNSTABLE | 0 | 0 | 0 | 30675/1/1 UNSTABLE | 69214/2/1 UNSTABLE | 0 | 0 | 0 |
| oracle-graalvm-25 | 28115/1/1 UNSTABLE | 80844/1/1 UNSTABLE | 87714/1/1 UNSTABLE | 0 | 0 | 0 | 30675/1/1 UNSTABLE | 69214/2/1 UNSTABLE | 0 | 0 | 0 |
| oracle-jdk-21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| oracle-jdk-25 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| oracle-openjdk-21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| oracle-openjdk-21.0.2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| oracle-openjdk-25 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| sapmachine-21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| semeru-21.0.10 | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| semeru-21.0.5 | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| semeru-25.0.2 | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| temurin-21-latest | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temurin-21.0.10 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temurin-21.0.11 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temurin-21.0.2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temurin-21.0.5 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temurin-21.0.8 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temurin-22 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temurin-23 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temurin-24 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temurin-25 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temurin-26 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| temurin-27 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| zulu-21 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| zulu-25 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

### Tier consistency (same runtime, Math hash default JIT vs -Xint)

- graalvm-ce-25: JIT != -Xint for pow; repetitions unstable for pow


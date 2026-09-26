# wp5-legacy-digest

Purpose: print the bitwise digest of the legacy (spine-less) network hydrocarbon path at WP4 (`959ea0d`), which
`src/test/java/com/wormzjl/createcheme/science/fluid/thermo/LegacyNetworkPathPinTest.java` pins as `WP4_DIGEST`.
Batch `2026-09-24-coolprop-low-temperature`, stage P3, WP5 (spine wiring). One-off; not a tracked harness.

Contents:
- `src/src/main/**`: `git archive 959ea0d src/main` (sources and resources), the closure listed in `sources.txt`.
- `src/runner/PrintLegacyDigest.java`: prints `LegacyNetworkPathPinTest.digest` and the revision.
- `sources.txt`: javac argument file (the 959ea0d closure, the pin test from the worktree, the runner).

Run (Git Bash, from the worktree root; JDK 21 = the Gradle toolchain; jars from the Gradle cache):

    G=C:/Users/wormz/.gradle/caches/modules-2/files-2.1
    CP="$G/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar;$G/org.junit.jupiter/junit-jupiter-api/5.11.4/308315b28e667db4091b2ba1f7aa220d1ddadb97/junit-jupiter-api-5.11.4.jar;$G/org.opentest4j/opentest4j/1.3.0/152ea56b3a72f655d4fd677fc0ef2596c3dd5e6e/opentest4j-1.3.0.jar;$G/org.apiguardian/apiguardian-api/1.1.2/a231e0d844d2721b0fa1b238006d15c6ded6842a/apiguardian-api-1.1.2.jar;$G/org.ejml/ejml-core/0.44.0/2450d402658e7907afa5fcf6c10b1ca2d9f30174/ejml-core-0.44.0.jar;$G/org.ejml/ejml-ddense/0.44.0/f0590aa927aaa74ce1e73ff34a8d6262e00de270/ejml-ddense-0.44.0.jar;$G/org.ejml/ejml-dsparse/0.44.0/6213b72811a9e41d8fdd94e4a578fa7002ae1add/ejml-dsparse-0.44.0.jar"
    "/c/Program Files/Java/jdk-21.0.11/bin/javac" -nowarn -encoding UTF-8 -d tools/wp5-legacy-digest/classes -cp "$CP" @tools/wp5-legacy-digest/sources.txt
    W=$(pwd -W)/tools/wp5-legacy-digest
    MSYS_NO_PATHCONV=1 "/c/Program Files/Java/jdk-21.0.11/bin/java" -Xshare:off -cp "$W/classes;$W/src/src/main/resources;$CP" com.wormzjl.createcheme.science.fluid.thermo.PrintLegacyDigest

Result (2026-09-25, JDK 21.0.11): revision
`f5e0178e5b8b45b3a00139e25bc0b7b58a259f25eff86dd040eca08263bcdf10:direct-liquid-v1:fluid-domain-data-v1:cp-segments-v1:catalog-volume-reference-v1`,
digest `d034d519c1daa46085eacb6cd7784f0b6dae54ff360733601cf029e234ca6cf1` (identical with `-Xint`). JDK 25.0.4 prints
`00f203a2...` on the same sources: the pin is specific to the toolchain JDK.

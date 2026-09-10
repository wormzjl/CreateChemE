$ErrorActionPreference = 'Stop'
foreach ($caseName in @('Default', 'DefaultPressureLow5')) {
    foreach ($heapSize in @('32m', '40m', '48m', '64m', '512m')) {
        foreach ($variant in @('baseline', 'candidate')) {
            $minimumHeap = if ($heapSize -in @('32m', '40m', '48m')) { $heapSize } else { '64m' }
            $runName = "round3-memory-$caseName-$heapSize-warm-$variant"
            .\gradlew.bat -I src/test/diagnostics/v3-ram-reduction/round3.init.gradle v3RamRound3Memory "-PramVariant=$variant" "-PramCase=$caseName" "-PramHeap=$heapSize" "-PramMinHeap=$minimumHeap" "-PramReport=build/reports/v3-ram-reduction/$runName.json" --no-configuration-cache --console=plain *> "build/ram-$runName.log"
            if ($LASTEXITCODE -ne 0) { Get-Content "build/ram-$runName.log" -Tail 25; exit $LASTEXITCODE }
        }
    }
    foreach ($pair in @(1, 2)) {
        foreach ($variant in @('baseline', 'candidate')) {
            $runName = "round3-memory-$caseName-512m-cold-$variant-$pair"
            .\gradlew.bat -I src/test/diagnostics/v3-ram-reduction/round3.init.gradle v3RamRound3Memory "-PramVariant=$variant" "-PramCase=$caseName" '-PramHeap=512m' '-PramMemoryWarmup=0' '-PramSamples=1' "-PramReport=build/reports/v3-ram-reduction/$runName.json" --no-configuration-cache --console=plain *> "build/ram-$runName.log"
            if ($LASTEXITCODE -ne 0) { Get-Content "build/ram-$runName.log" -Tail 25; exit $LASTEXITCODE }
        }
    }
}
foreach ($variant in @('baseline', 'candidate')) {
    $runName = "round3-memory-Default-512m-long-$variant"
    .\gradlew.bat -I src/test/diagnostics/v3-ram-reduction/round3.init.gradle v3RamRound3Memory "-PramVariant=$variant" '-PramCase=Default' '-PramHeap=512m' '-PramSamples=10' "-PramReport=build/reports/v3-ram-reduction/$runName.json" --no-configuration-cache --console=plain *> "build/ram-$runName.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "build/ram-$runName.log" -Tail 25; exit $LASTEXITCODE }
}

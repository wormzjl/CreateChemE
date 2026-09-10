$ErrorActionPreference = 'Stop'
$revision = '8547feaeffbda5daf2ee9569bbd37d566d46e447'
$rootPath = (git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Run from the repository checkout.' }
$packagePath = 'com/wormzjl/createcheme/science/column/v3'
$names = @('V3FiniteDifferenceJacobian', 'V3SimultaneousColumnSolver', 'V3BlockJacobian',
    'V3NormalEquations', 'linalg/V3BandedPivotedSolver')
$rebuildRoot = 'build/generated/ram-round3-rebuild'
foreach ($name in $names) {
    $relativePath = "src/main/java/$packagePath/$name.java"
    $sourceLines = git show "$($revision):$relativePath"
    if ($LASTEXITCODE -ne 0) { throw "Cannot read $relativePath" }
    $outputPath = Join-Path $rootPath "$rebuildRoot/$relativePath"
    [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($outputPath)) | Out-Null
    [System.IO.File]::WriteAllText($outputPath, ($sourceLines -join "`n") + "`n")
}
# This saved patch contains only the earlier finite-difference and block ownership changes.
git apply "--directory=$rebuildRoot" src/test/diagnostics/v3-ram-reduction/pre-round3.patch
if ($LASTEXITCODE -ne 0) { throw 'Cannot apply the pre-round-three source patch.' }
$manifest = foreach ($name in $names) {
    $relativePath = "src/main/java/$packagePath/$name.java"
    $sourceText = [System.IO.File]::ReadAllText((Join-Path $rootPath "$rebuildRoot/$relativePath")).Replace("`r`n", "`n")
    $outputPath = Join-Path $rootPath "build/generated/ram-round3-baseline/$relativePath"
    if (Test-Path -LiteralPath $outputPath) {
        $existing = [System.IO.File]::ReadAllText($outputPath).Replace("`r`n", "`n")
        if ($existing -cne $sourceText) { throw "Frozen source differs: $name" }
    }
    [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($outputPath)) | Out-Null
    [System.IO.File]::WriteAllText($outputPath, $sourceText)
    [pscustomobject]@{ Path = $relativePath; SHA256 = (Get-FileHash -LiteralPath $outputPath).Hash; BaseRevision = $revision }
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $rootPath 'build/generated/ram-round3-baseline/manifest.json')

param([string]$Revision = '8547fea')
$ErrorActionPreference = 'Stop'
$rootPath = (git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Run from the repository checkout.' }
$packagePath = 'com/wormzjl/createcheme/science/column/v3'
$names = @('V3FiniteDifferenceJacobian', 'V3BlockJacobian', 'V3BlockJacobianAssembler', 'V3DegreeOfFreedomLedger')
$manifest = @()
foreach ($name in $names) {
    $relativePath = "src/main/java/$packagePath/$name.java"
    $sourceLines = git show "$($Revision):$relativePath"
    if ($LASTEXITCODE -ne 0) { throw "Cannot read $relativePath at $Revision" }
    $outputPath = Join-Path $rootPath "build/generated/ram-baseline/$packagePath/$name.java"
    [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($outputPath)) | Out-Null
    [System.IO.File]::WriteAllText($outputPath, ($sourceLines -join "`n") + "`n")
    $blob = (git rev-parse "$($Revision):$relativePath").Trim()
    $manifest += [pscustomobject]@{ Path = $relativePath; Blob = $blob; Revision = $Revision }
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $rootPath 'build/generated/ram-baseline/manifest.json')

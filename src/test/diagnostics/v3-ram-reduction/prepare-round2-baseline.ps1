$ErrorActionPreference = 'Stop'
$revision = '8547feaeffbda5daf2ee9569bbd37d566d46e447'
$rootPath = (git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Run from the repository checkout.' }
$packagePath = 'com/wormzjl/createcheme/science/column/v3'
$names = @('V3BlockJacobianAssembler', 'V3DryMeshState', 'V3DryMeshCoordinateMap',
    'V3MeshResidual', 'thermo/V3FugacityResult', 'V3StageBlockLayout')
$manifest = foreach ($name in $names) {
    $relativePath = "src/main/java/$packagePath/$name.java"
    $sourceLines = git show "$($revision):$relativePath"
    if ($LASTEXITCODE -ne 0) { throw "Cannot read $relativePath at $revision" }
    $sourceText = ($sourceLines -join "`n") + "`n"
    if ($name -eq 'V3BlockJacobianAssembler') {
        # Round one had exactly these two ownership-factory changes in this class.
        $needle = 'return new V3BlockJacobian(layout, lower, diagonal, upper, '
        if (($sourceText.Split($needle).Length - 1) -ne 2) { throw 'Unexpected assembler baseline.' }
        $sourceText = $sourceText.Replace($needle, 'return V3BlockJacobian.fromOwnedBlocks(layout, lower, diagonal, upper, ')
    }
    $outputPath = Join-Path $rootPath "build/generated/ram-round2-baseline/$packagePath/$name.java"
    if (Test-Path -LiteralPath $outputPath) {
        $existing = [System.IO.File]::ReadAllText($outputPath).Replace("`r`n", "`n")
        if ($existing -cne $sourceText) { throw "Frozen source differs: $name" }
    }
    [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($outputPath)) | Out-Null
    [System.IO.File]::WriteAllText($outputPath, $sourceText)
    [pscustomobject]@{ Path = $relativePath; SHA256 = (Get-FileHash -LiteralPath $outputPath).Hash; BaseRevision = $revision }
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $rootPath 'build/generated/ram-round2-baseline/manifest.json')

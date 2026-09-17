param(
    [string]$Dwsim = 'C:/Program Files/DWSIM',
    [Parameter(Mandatory = $true)][string]$Characterization,
    [switch]$Ambient
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$source = (Join-Path $PSScriptRoot 'Export-DwsimViscosity.cs')
$exe = Join-Path $repo 'build/Export-DwsimViscosity.exe'
$characterizationPath = (Resolve-Path -LiteralPath $Characterization).Path
& 'C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe' /nologo /platform:x64 /r:Microsoft.CSharp.dll /r:System.Web.Extensions.dll "/out:$exe" $source
if ($LASTEXITCODE -ne 0) { throw 'Failed to compile DWSIM exporter' }
Copy-Item -LiteralPath (Join-Path $Dwsim 'DWSIM.exe.config') -Destination "$exe.config"
$exportArgs = @($Dwsim, $characterizationPath, (Join-Path $repo 'src/main/resources/data/createcheme/materials/properties'))
if ($Ambient) { $exportArgs += (Join-Path $repo 'build/dwsim-ambient-viscosity-export.json'); $exportArgs += '--ambient' }
else { $exportArgs += (Join-Path $repo 'build/dwsim-viscosity-export.json') }
& $exe @exportArgs
if ($LASTEXITCODE -ne 0) { throw 'DWSIM viscosity export failed' }

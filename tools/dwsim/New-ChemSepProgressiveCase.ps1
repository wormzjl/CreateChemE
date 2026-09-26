param(
  [Parameter(Mandatory)][string]$Directory,
  [double]$SteamFraction=0,
  [double[]]$PaFractions=@(0,0,0),
  [double]$SideDrawFraction=1,
  [switch]$DryComponentList,
  [switch]$SaturatedCondenser,
  [int]$MaximumIterations=60
)
$ErrorActionPreference='Stop'
if($SteamFraction -lt 0 -or $SteamFraction -gt 1 -or $SideDrawFraction -lt 0 -or $SideDrawFraction -gt 1 -or $PaFractions.Count -ne 3 -or @($PaFractions|Where-Object {$_ -lt 0 -or $_ -gt 1}).Count){throw 'Invalid continuation factors.'}
if($DryComponentList -and $SteamFraction -gt 0){throw 'Steam requires Water.'}
$null=New-Item -ItemType Directory -Force $Directory
$data=Get-Content build/dwsim-research/chemsep-v3/input.json -Raw|ConvertFrom-Json
$steam=@($data.input.steamFeeds)
$pas=@($data.input.pumparounds)
if($SteamFraction -eq 0){$data.input.steamFeeds=@()}else{$data.input.steamFeeds=@($steam|ForEach-Object {$_.molarFlowMolPerSecond*=$SteamFraction; $_})}
if($SideDrawFraction -eq 0){$data.input.sideDraws=@()}else{$data.input.sideDraws=@($data.input.sideDraws|ForEach-Object {$_.molarFlowMolPerSecond*=$SideDrawFraction; $_})}
$heat=[ordered]@{}; $activePas=@()
for($i=0;$i -lt 3;$i++){
  if($PaFractions[$i] -eq 0){continue}
  $pa=$pas[$i]; $pa.dutyWatts*=$PaFractions[$i]; $activePas+=,$pa
  for($stage=$pa.returnTray;$stage -le $pa.drawTray;$stage++){$heat[[string]$stage]=$pa.dutyWatts/($pa.drawTray-$pa.returnTray+1)}
}
$data.input.pumparounds=$activePas
$data.tray_heat_W=[pscustomobject]$heat
$data|Add-Member include_water (-not $DryComponentList)
$data|ConvertTo-Json -Depth 25|Set-Content "$Directory/input.json"
Copy-Item -LiteralPath build/dwsim-research/chemsep-v3/source-feed.dwxml -Destination "$Directory/source-feed.dwxml"
$native=Get-Content build/dwsim-research/chemsep-v3/native-properties.json -Raw|ConvertFrom-Json
if($DryComponentList){$native.components=@($native.components|Where-Object name -ne Water)}
# The worker regenerates all native properties, including the correctly sized kij matrix.
$native|ConvertTo-Json -Depth 12|Set-Content "$Directory/native-properties.json"
& "$PSScriptRoot/Build-ChemSepV3Case.ps1" -Directory $Directory -NoWaterDraw:($SteamFraction -eq 0) -SaturatedCondenser:$SaturatedCondenser
$sep=Get-Content "$Directory/input.sep" -Raw
$sep=$sep -replace '(?m)^\d+ Maximum iterations',"$MaximumIterations Maximum iterations"
[IO.File]::WriteAllText([IO.Path]::GetFullPath("$Directory/input.sep"),$sep,[Text.Encoding]::ASCII)
[ordered]@{steam_fraction=$SteamFraction;steam_mol_s=$SteamFraction*333.3333333333333;pa_fractions=$PaFractions;cooling_MW=-($activePas|Measure-Object dutyWatts -Sum).Sum/1e6;side_draw_fraction=$SideDrawFraction;include_water=(-not $DryComponentList);condenser=$(if($SaturatedCondenser){'saturated'}else{'subcooled 332.15 K'});maximum_iterations=$MaximumIterations;source_hash=$data.source_block_sha256}|ConvertTo-Json -Depth 5|Set-Content "$Directory/progression.json"

# Review 8.7 runner: one Gradle invocation of the probes (or given tests) with the run 87b backward-Euler set plus extra switches.
# Usage: run-probe.ps1 -Label run91a-interval0.1 [-Tests '*JunctionHoldupTransientProbe'] [-Extra '-PjunctionInterval=5','...'] [-NoBase] [-GateDir runNN-gates]
param([string]$Label,[string[]]$Tests=@('*JunctionHoldupStaticProbe','*JunctionHoldupTransientProbe'),[string[]]$Extra=@(),[switch]$NoBase,[switch]$NoInit,[string]$GateDir='')
$root='D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/remove-agents-attribution-line-3d0c97'
Set-Location $root
$env:JAVA_HOME='C:/Program Files/Java/jdk-21.0.11'; $env:JAVA_OPTS='-Xshare:off'
$T='tools/junction-holdup-prototype'
$BASE=@('-PcapForm=B2','-PrateForm=frozen','-PrateWarmStart=fresh','-PvoidStart=history2','-PmintHoldup=on','-PstageForm=implicit','-PholdupTau=0.05','-PpinMass=on','-PstageClip=on','-PsolverDiag=true','-Pintegrator=be','-PbeModeRule=off','-PbeColdStart=rate','-PbeStateCap=0.05')
if($NoBase){$BASE=@()}
$testArgs=@();foreach($tn in $Tests){$testArgs+="--tests";$testArgs+=$tn}
if(Test-Path build/test-results/test){Remove-Item -Recurse -Force build/test-results/test}
$init=@('-I',"$T/holdup.init.gradle");if($NoInit){$init=@()}
$cmd="./gradlew.bat --no-configuration-cache $($init -join ' ') test $($testArgs -join ' ') $($BASE -join ' ') $($Extra -join ' ') --console=plain --continue"
"COMMAND: $cmd" | Out-File -Encoding utf8 "$T/$Label.log"
& ./gradlew.bat --no-configuration-cache @init test @testArgs @BASE @Extra --console=plain --continue *>> "$T/$Label.log"
$run=($Label -split '-')[0]
if($GateDir -ne ''){New-Item -ItemType Directory -Force "$T/$GateDir" | Out-Null; Copy-Item build/test-results/test/*.xml "$T/$GateDir/"}
else{
 if(Test-Path build/test-results/test/TEST-com.wormzjl.createcheme.runtime.fluid.JunctionHoldupStaticProbe.xml){Copy-Item build/test-results/test/TEST-com.wormzjl.createcheme.runtime.fluid.JunctionHoldupStaticProbe.xml "$T/$run-static.xml"}
 if(Test-Path build/test-results/test/TEST-com.wormzjl.createcheme.runtime.fluid.JunctionHoldupTransientProbe.xml){Copy-Item build/test-results/test/TEST-com.wormzjl.createcheme.runtime.fluid.JunctionHoldupTransientProbe.xml "$T/$run-transient.xml"}
 Get-ChildItem build/test-results/test/*.xml | Where-Object { $_.Name -notmatch 'JunctionHoldup(Static|Transient)Probe' } | ForEach-Object { Copy-Item $_ "$T/$run-$($_.Name -replace '^TEST-com.wormzjl.createcheme.','')" }
}
Get-Content "$T/$Label.log" | Select-String -Pattern 'BUILD|FAILED|error:|tests completed' | Select-Object -Last 12

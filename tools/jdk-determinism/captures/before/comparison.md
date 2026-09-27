# Sweep comparison: $SP/jdk-sweep/cap-before

Reference runtime: `system` (21.0.10+7-Ubuntu-124.04).

## Runtimes and summary

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

Letters name groups of byte-identical output (A = the reference's group). chain-100 exact = the maximum relative moles deviation of `harness.sh regression` against the checked-in `chain-100.json`.

## Junction lines by group

- group A (22): system, corretto-21, dragonwell-21, graalvm-ce-21, kona-21, liberica-21, microsoft-21, oracle-graalvm-21, oracle-jdk-21, oracle-openjdk-21, oracle-openjdk-21.0.2, sapmachine-21, temurin-21-latest, temurin-21.0.10, temurin-21.0.11, temurin-21.0.2, temurin-21.0.5, temurin-21.0.8, temurin-22, temurin-23, temurin-24, zulu-21; 0 of 33 lines differ from the reference
- group B (11): corretto-25, graalvm-ce-25, liberica-25, microsoft-25, oracle-graalvm-25, oracle-jdk-25, oracle-openjdk-25, temurin-25, temurin-26, temurin-27, zulu-25; 24 of 33 lines differ from the reference
- group C (3): semeru-21.0.10, semeru-21.0.5, semeru-25.0.2; 28 of 33 lines differ from the reference

### group B vs reference (first member `corretto-25`)

```
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:4:false, worstMoles=5.514587206174876E-16, worstEnergy=1.2785353309169641E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:4:false, worstMoles=1.1718497813121612E-15, worstEnergy=1.1623048462881492E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:4:true, worstMoles=1.2013212947883937E-15, worstEnergy=2.9155899314157096E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:4:true, worstMoles=1.2936517102717031E-15, worstEnergy=2.4199396430750387E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:5:false, worstMoles=9.995189311191963E-16, worstEnergy=2.063091102161465E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:5:false, worstMoles=8.587395909448297E-16, worstEnergy=1.191362467445353E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:5:true, worstMoles=7.556908400314501E-16, worstEnergy=2.886434032101552E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:5:true, worstMoles=7.900404236692433E-16, worstEnergy=1.9826011533626826E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:6:false, worstMoles=9.61788341858209E-16, worstEnergy=8.71728634716112E-16, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:6:false, worstMoles=8.616542509648244E-16, worstEnergy=1.0170167405021306E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:6:true, worstMoles=8.915014561666219E-16, worstEnergy=2.769810434844924E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:6:true, worstMoles=8.587395909448297E-16, worstEnergy=3.1488371259289664E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:4:false, worstMoles=5.10099316571176E-15, worstEnergy=5.4395265571948675E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:4:false, worstMoles=2.2485584582390825E-15, worstEnergy=2.3688260813590553E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:4:true, worstMoles=1.8286551733222133E-15, worstEnergy=4.607488907237493E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:4:true, worstMoles=3.190785275077371E-15, worstEnergy=4.371207424815058E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:5:false, worstMoles=3.0662160794169303E-15, worstEnergy=5.731974221560183E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:5:false, worstMoles=1.9990378622383926E-15, worstEnergy=4.415959731916263E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:5:true, worstMoles=1.2841621953470297E-15, worstEnergy=1.8311814887738755E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:5:true, worstMoles=1.1924173642176952E-15, worstEnergy=1.9788574152878976E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:6:false, worstMoles=1.3627627019630802E-15, worstEnergy=7.603639273498202E-16, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:6:false, worstMoles=1.7375224450029273E-15, worstEnergy=6.43384861603694E-16, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:6:true, worstMoles=7.835885536287712E-16, worstEnergy=2.864912974372031E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:6:true, worstMoles=6.813813509815401E-16, worstEnergy=2.5695611213439864E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:4:false, worstMoles=2.060975018267591E-16, worstEnergy=2.324609692576299E-16, newtonSolves=16,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:4:false, worstMoles=1.0304875091337955E-16, worstEnergy=2.324609692576299E-16, newtonSolves=16,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:4:true, worstMoles=3.2632104455903524E-16, worstEnergy=7.288974828539274E-16, newtonSolves=16,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:4:true, worstMoles=3.2632104455903524E-16, worstEnergy=3.4987079176988513E-16, newtonSolves=16,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:5:false, worstMoles=4.825263805403017E-16, worstEnergy=6.392676654584821E-16, newtonSolves=20,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:5:false, worstMoles=2.584962752894473E-16, worstEnergy=3.4869145388644477E-16, newtonSolves=20,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:5:true, worstMoles=4.121950036535182E-16, worstEnergy=4.081825903981993E-16, newtonSolves=17,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:5:true, worstMoles=4.121950036535182E-16, worstEnergy=1.7493539588494257E-16, newtonSolves=17,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:6:false, worstMoles=4.1359404046311573E-16, worstEnergy=4.35864317358056E-16, newtonSolves=20,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:6:false, worstMoles=4.1359404046311573E-16, worstEnergy=4.939795596724634E-16, newtonSolves=20,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:6:true, worstMoles=2.7479666910234547E-16, worstEnergy=3.2071489245572804E-16, newtonSolves=19,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:6:true, worstMoles=2.060975018267591E-16, worstEnergy=1.4577949657078547E-16, newtonSolves=19,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:4:false, worstMoles=2.4126319027015086E-16, worstEnergy=3.21692430801847E-16, newtonSolves=28,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:4:false, worstMoles=7.495194860796941E-16, worstEnergy=3.21692430801847E-16, newtonSolves=28,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:4:true, worstMoles=5.968135467490589E-16, worstEnergy=5.611685207532844E-16, newtonSolves=26,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:4:true, worstMoles=6.473122834324631E-16, worstEnergy=7.9745000317572E-16, newtonSolves=26,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:5:false, worstMoles=2.72552540392616E-16, worstEnergy=2.924476643653155E-16, newtonSolves=33,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:5:false, worstMoles=4.76966945687078E-16, worstEnergy=2.632028979287839E-16, newtonSolves=33,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:5:true, worstMoles=5.780909902760545E-16, worstEnergy=1.7721111181682666E-16, newtonSolves=27,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:5:true, worstMoles=2.296719085147324E-16, worstEnergy=8.565203737813288E-16, newtonSolves=27,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:6:false, worstMoles=2.38483472843539E-16, worstEnergy=2.339581314922524E-16, newtonSolves=35,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:6:false, worstMoles=2.0679702023155786E-16, worstEnergy=5.264057958575678E-16, newtonSolves=35,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:6:true, worstMoles=1.6350177741836058E-16, worstEnergy=5.611685207532844E-16, newtonSolves=29,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:6:true, worstMoles=2.891096134821129E-16, worstEnergy=3.8395740893645773E-16, newtonSolves=29,]
```

### group C vs reference (first member `semeru-21.0.10`)

```
- LIQUID_JUNCTION interval=0.1 tanks:0.02 m_J=1.564 kg Q(1s)=3.103e-12 kg/s m_J/Q=5.040e+11 s tauFit(1.0-1.1s)=1.003e+12 s mismatch(1s)=-0.983 mismatch(end)=NaN
+ LIQUID_JUNCTION interval=0.1 tanks:0.02 m_J=1.564 kg Q(1s)=5.000e-12 kg/s m_J/Q=3.128e+11 s tauFit(1.0-1.1s)=7.383e+12 s mismatch(1s)=-0.983 mismatch(end)=0.0167
- LIQUID_JUNCTION interval=0.1 tanks:0.05 m_J=9.774 kg Q(1s)=2.740e-11 kg/s m_J/Q=3.567e+11 s tauFit(1.0-1.1s)=Infinity s mismatch(1s)=-0.427 mismatch(end)=-0.427
+ LIQUID_JUNCTION interval=0.1 tanks:0.05 m_J=9.774 kg Q(1s)=1.562e-11 kg/s m_J/Q=6.258e+11 s tauFit(1.0-1.1s)=Infinity s mismatch(1s)=0.00294 mismatch(end)=0.00294
- LIQUID_JUNCTION interval=5 tanks:0.02 m_J=1.564 kg Q(1s)=0.01503 kg/s m_J/Q=104.0 s mismatch(1s)=-0.342 mismatch(end)=-0.0806 perSlice=[-0.342,-0.0806,-0.0806,-0.0806] newtonSolves=17
+ LIQUID_JUNCTION interval=5 tanks:0.02 m_J=1.564 kg Q(1s)=0.01503 kg/s m_J/Q=104.0 s mismatch(1s)=-0.342 mismatch(end)=-0.894 perSlice=[-0.342,-0.894,-0.894,-0.894] newtonSolves=17
- LIQUID_JUNCTION interval=5 tanks:0.05 m_J=9.774 kg Q(1s)=0.01503 kg/s m_J/Q=650.2 s mismatch(1s)=-0.356 mismatch(end)=-0.472 perSlice=[-0.356,-0.472,-0.472,-0.472] newtonSolves=17
+ LIQUID_JUNCTION interval=5 tanks:0.05 m_J=9.774 kg Q(1s)=0.01503 kg/s m_J/Q=650.2 s mismatch(1s)=-0.356 mismatch(end)=0.00295 perSlice=[-0.356,0.00295,0.00295,0.00295] newtonSolves=17
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:4:false, worstMoles=5.514587206174876E-16, worstEnergy=1.2785353309169641E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:4:false, worstMoles=7.927219108876385E-16, worstEnergy=7.264405289300933E-16, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:4:true, worstMoles=1.2013212947883937E-15, worstEnergy=2.9155899314157096E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:4:true, worstMoles=1.3396337618739343E-15, worstEnergy=2.2158483478759394E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:5:false, worstMoles=9.995189311191963E-16, worstEnergy=2.063091102161465E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:5:false, worstMoles=9.305865910420103E-16, worstEnergy=2.3246096925762984E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:5:true, worstMoles=7.556908400314501E-16, worstEnergy=2.886434032101552E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:5:true, worstMoles=1.6144304309762797E-15, worstEnergy=2.2158483478759394E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:6:false, worstMoles=9.61788341858209E-16, worstEnergy=8.71728634716112E-16, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:6:false, worstMoles=1.2022354273227615E-15, worstEnergy=7.55498150087297E-16, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:6:true, worstMoles=8.915014561666219E-16, worstEnergy=2.769810434844924E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.02:6:true, worstMoles=7.61982401113646E-16, worstEnergy=2.186692448561782E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:4:false, worstMoles=5.10099316571176E-15, worstEnergy=5.4395265571948675E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:4:false, worstMoles=5.204391675827539E-15, worstEnergy=5.761218987996714E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:4:true, worstMoles=1.8286551733222133E-15, worstEnergy=4.607488907237493E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:4:true, worstMoles=1.752482580548483E-15, worstEnergy=4.991446316173951E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:5:false, worstMoles=3.0662160794169303E-15, worstEnergy=5.731974221560183E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:5:false, worstMoles=1.654376161852463E-15, worstEnergy=4.0942673011144166E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:5:true, worstMoles=1.2841621953470297E-15, worstEnergy=1.8311814887738755E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:5:true, worstMoles=9.880029589232332E-16, worstEnergy=1.5062944504430265E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:6:false, worstMoles=1.3627627019630802E-15, worstEnergy=7.603639273498202E-16, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:6:false, worstMoles=9.19864823825079E-16, worstEnergy=1.9009098183745505E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:6:true, worstMoles=7.835885536287712E-16, worstEnergy=2.864912974372031E-15, newtonSolves=0,]
+ MIXED_GAS_TRANSIENT interval=0.1 Outcome[key=0.05:6:true, worstMoles=6.473122834324631E-16, worstEnergy=2.4218851948299643E-15, newtonSolves=0,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:4:false, worstMoles=2.060975018267591E-16, worstEnergy=2.324609692576299E-16, newtonSolves=16,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:4:false, worstMoles=2.060975018267591E-16, worstEnergy=4.939795596724634E-16, newtonSolves=16,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:4:true, worstMoles=3.2632104455903524E-16, worstEnergy=7.288974828539274E-16, newtonSolves=16,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:4:true, worstMoles=4.465445872913114E-16, worstEnergy=2.3324719451325675E-16, newtonSolves=16,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:5:false, worstMoles=4.825263805403017E-16, worstEnergy=6.392676654584821E-16, newtonSolves=20,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:5:false, worstMoles=2.4044708546455227E-16, worstEnergy=3.1963383272924104E-16, newtonSolves=20,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:5:true, worstMoles=4.121950036535182E-16, worstEnergy=4.081825903981993E-16, newtonSolves=17,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:5:true, worstMoles=2.4044708546455227E-16, worstEnergy=2.9155899314157094E-16, newtonSolves=17,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:6:false, worstMoles=4.1359404046311573E-16, worstEnergy=4.35864317358056E-16, newtonSolves=20,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:6:false, worstMoles=4.808941709291045E-16, worstEnergy=1.7434572694322239E-16, newtonSolves=20,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:6:true, worstMoles=2.7479666910234547E-16, worstEnergy=3.2071489245572804E-16, newtonSolves=19,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.02:6:true, worstMoles=5.839429218424842E-16, worstEnergy=3.2071489245572804E-16, newtonSolves=19,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:4:false, worstMoles=2.4126319027015086E-16, worstEnergy=3.21692430801847E-16, newtonSolves=28,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:4:false, worstMoles=3.446617003859298E-16, worstEnergy=2.339581314922524E-16, newtonSolves=28,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:4:true, worstMoles=5.968135467490589E-16, worstEnergy=5.611685207532844E-16, newtonSolves=26,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:4:true, worstMoles=3.4136606391190264E-16, worstEnergy=2.9535185302804443E-16, newtonSolves=26,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:5:false, worstMoles=2.72552540392616E-16, worstEnergy=2.924476643653155E-16, newtonSolves=33,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:5:false, worstMoles=3.101955303473368E-16, worstEnergy=4.679162629845048E-16, newtonSolves=33,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:5:true, worstMoles=5.780909902760545E-16, worstEnergy=1.7721111181682666E-16, newtonSolves=27,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:5:true, worstMoles=2.7057941204136535E-16, worstEnergy=3.544222236336533E-16, newtonSolves=27,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:6:false, worstMoles=2.38483472843539E-16, worstEnergy=2.339581314922524E-16, newtonSolves=35,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:6:false, worstMoles=4.76966945687078E-16, worstEnergy=2.924476643653155E-16, newtonSolves=35,]
- MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:6:true, worstMoles=1.6350177741836058E-16, worstEnergy=5.611685207532844E-16, newtonSolves=29,]
+ MIXED_GAS_TRANSIENT interval=5 Outcome[key=0.05:6:true, worstMoles=4.76966945687078E-16, worstEnergy=4.725629648448711E-16, newtonSolves=29,]
```

## BitwiseProbe groups: differing values against the reference (text files print one hex double per line; chain-100.json counts numbers)

| group | runtimes | chain-100.json | scenarios.txt | gas-ports.txt | liquid-ports.txt |
|---|---|---|---|---|---|
| A | system, corretto-21, dragonwell-21, graalvm-ce-21, kona-21, liberica-21, microsoft-21, oracle-graalvm-21, oracle-jdk-21, oracle-openjdk-21, oracle-openjdk-21.0.2, sapmachine-21, temurin-21-latest, temurin-21.0.10, temurin-21.0.11, temurin-21.0.2, temurin-21.0.5, temurin-21.0.8, temurin-22, temurin-23, temurin-24, zulu-21 | 0 / 3003 numbers | 0 / 3460 lines | 0 / 997 lines | 0 / 608 lines |
| B | corretto-25, graalvm-ce-25, liberica-25, microsoft-25, oracle-graalvm-25, oracle-jdk-25, oracle-openjdk-25, temurin-25, temurin-26, temurin-27, zulu-25 | 2721 / 3003 numbers | 1796 / 3460 lines | 418 / 997 lines | 252 / 608 lines |
| C | semeru-21.0.10, semeru-21.0.5, semeru-25.0.2, system (-UseLibmIntrinsic), corretto-21 (-UseLibmIntrinsic), corretto-25 (-UseLibmIntrinsic), dragonwell-21 (-UseLibmIntrinsic), kona-21 (-UseLibmIntrinsic), liberica-21 (-UseLibmIntrinsic), liberica-25 (-UseLibmIntrinsic), microsoft-21 (-UseLibmIntrinsic), microsoft-25 (-UseLibmIntrinsic), oracle-jdk-21 (-UseLibmIntrinsic), oracle-jdk-25 (-UseLibmIntrinsic), oracle-openjdk-21 (-UseLibmIntrinsic), oracle-openjdk-21.0.2 (-UseLibmIntrinsic), oracle-openjdk-25 (-UseLibmIntrinsic), sapmachine-21 (-UseLibmIntrinsic), temurin-21-latest (-UseLibmIntrinsic), temurin-21.0.10 (-UseLibmIntrinsic), temurin-21.0.11 (-UseLibmIntrinsic), temurin-21.0.2 (-UseLibmIntrinsic), temurin-21.0.5 (-UseLibmIntrinsic), temurin-21.0.8 (-UseLibmIntrinsic), temurin-22 (-UseLibmIntrinsic), temurin-23 (-UseLibmIntrinsic), temurin-24 (-UseLibmIntrinsic), temurin-25 (-UseLibmIntrinsic), temurin-26 (-UseLibmIntrinsic), temurin-27 (-UseLibmIntrinsic), zulu-21 (-UseLibmIntrinsic), zulu-25 (-UseLibmIntrinsic) | 2719 / 3003 numbers | 2077 / 3460 lines | 482 / 997 lines | 247 / 608 lines |
| D | graalvm-ce-21 (-UseLibmIntrinsic) | 2762 / 3003 numbers | 2110 / 3460 lines | 457 / 997 lines | 234 / 608 lines |
| E | graalvm-ce-25 (-UseLibmIntrinsic) | 2768 / 3003 numbers | 2114 / 3460 lines | 457 / 997 lines | 234 / 608 lines |
| F | oracle-graalvm-21 (-UseLibmIntrinsic) | 2686 / 3003 numbers | 2114 / 3460 lines | 457 / 997 lines | 234 / 608 lines |
| G | oracle-graalvm-25 (-UseLibmIntrinsic) | 2759 / 3003 numbers | 2111 / 3460 lines | 457 / 997 lines | 234 / 608 lines |

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
| graalvm-ce-21 | 28115/1/1 UNSTABLE | 80844/1/1 UNSTABLE | 87714/1/1 UNSTABLE | 0 | 0 | 0 | 0 | 68783/2/2 UNSTABLE | 0 | 0 | 0 |
| graalvm-ce-25 | 0 | 80844/1/1 UNSTABLE | 87715/1/2 UNSTABLE | 0 | 0 | 0 | 30675/1/1 UNSTABLE | 69214/2/1 UNSTABLE | 0 | 0 | 0 |
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


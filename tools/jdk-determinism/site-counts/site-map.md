| file | sites (function x count) | executed by the fluid gates (sites, calls) |
|---|---|---|
| science/column/v3/HollandB12Thermo.java | log 1 | 0 sites, 0 calls |
| science/column/v3/IndependentHollandMeshOracle.java | exp 2, log 4 | 0 sites, 0 calls |
| science/column/v3/V3AcceptanceAuditor.java | exp 1 | 0 sites, 0 calls |
| science/column/v3/V3AnchorTransformerInitializer.java | exp 2 | 0 sites, 0 calls |
| science/column/v3/V3ColumnCalculator.java | log10 1, pow 1 | 0 sites, 0 calls |
| science/column/v3/V3ColumnInitializer.java | exp 3, log 6 | 0 sites, 0 calls |
| science/column/v3/V3DryMeshCoordinateMap.java | exp 2, log 2 | 0 sites, 0 calls |
| science/column/v3/V3FactorizedNeuralFeatures.java | exp 1, expm1 2, log 4, log1p 3 | 0 sites, 0 calls |
| science/column/v3/V3FreeWaterContinuation.java | log 1 | 0 sites, 0 calls |
| science/column/v3/V3GeneralNeuralFeatures.java | expm1 1, log 1, log1p 3 | 0 sites, 0 calls |
| science/column/v3/V3HollandExample32.java | exp 2 | 0 sites, 0 calls |
| science/column/v3/V3MeshResidualEvaluator.java | log 7 | 0 sites, 0 calls |
| science/column/v3/V3StageEquilibriumRatios.java | exp 1 | 0 sites, 0 calls |
| science/column/v3/V3TrayHydraulics.java | cbrt 1, exp 3, pow 8 | 0 sites, 0 calls |
| science/column/v3/thermo/V3FeedFlash.java | exp 3, expm1 2, log 1 | 0 sites, 0 calls |
| science/column/v3/thermo/V3PropertyComponent.java | pow 2 | 0 sites, 0 calls |
| science/column/v3/thermo/V3TruncatedFlash.java | exp 2, expm1 1, log 7 | 0 sites, 0 calls |
| science/column/v3/thermo/V3WaterProperties.java | exp 1, pow 10 | 5 sites, 5,391,608 calls |
| science/fluid/network/FlowControl.java | log1p 1 | 1 sites, 246,272 calls |
| science/fluid/network/PipeResistance.java | log 1, log10 1, pow 4 | 6 sites, 355,951,116 calls |
| science/fluid/solver/PhaseLayout.java | exp 6, log 15 | 21 sites, 34,344,775 calls |
| science/fluid/thermo/FluidThermodynamics.java | exp 3, log 5 | 8 sites, 766,146,618 calls |
| science/fluid/thermo/GlobalLiquidResponse.java | exp 1, expm1 1, log 2 | 4 sites, 266,031,104 calls |
| science/fluid/thermo/HydrocarbonModel.java | exp 1 | 1 sites, 9,187 calls |
| science/fluid/thermo/TranslatedPengRobinson.java | log 1, pow 2 | 3 sites, 80,183,820 calls |
| science/fluid/transport/MixtureViscosity.java | exp 1, log 1 | 2 sites, 1,717,114 calls |
| science/fluid/transport/SlurryTransport.java | pow 1 | 1 sites, 69,455 calls |
| science/material/ViscosityCorrelation.java | exp 2, log 4, pow 2 | 5 sites, 53,667,216 calls |
| science/thermo/PengRobinson78.java | acos 1, cbrt 3, cos 3, exp 1, log 3 | 8 sites, 229,048 calls |
| science/thermo/PengRobinsonKernel.java | acos 1, cbrt 5, cos 3, exp 1, log 4 | 10 sites, 160,367,833 calls |

Executed sites (line: function, calls over science + runtime + adjacent + regression + probe):

- science/column/v3/thermo/V3WaterProperties.java: 44 pow 1,347,788; 45 pow 1,347,788; 46 pow 1,347,788; 47 exp 1,347,788; 138 pow 456
- science/fluid/network/FlowControl.java: 94 log1p 246,272
- science/fluid/network/PipeResistance.java: 33 pow 71,603,017; 36 pow 40,621,157; 37 log10 40,621,157; 38 log 40,621,157; 38 pow 81,242,314; 38 pow 81,242,314
- science/fluid/solver/PhaseLayout.java: 130 exp 2,681,814; 153 log 1,057,098; 153 log 1,057,098; 156 log 6,628; 163 log 33,665; 173 log 2,153,002; 173 log 2,153,002; 174 log 228,848; 188 exp 994,934; 189 exp 1,460,350; 198 exp 5,363,686; 198 exp 5,363,686; 199 exp 833,521; 280 log 9,312; 315 log 5,065,182; 315 log 5,065,182; 317 log 753,634; 348 log 9,022; 349 log 9,022; 354 log 45,981; 355 log 108
- science/fluid/thermo/FluidThermodynamics.java: 490 exp 7,850,712; 490 log 7,850,712; 506 log 235,537,138; 511 exp 220,925,878; 511 log 220,925,878; 513 exp 14,611,260; 513 log 29,222,520; 513 log 29,222,520
- science/fluid/thermo/GlobalLiquidResponse.java: 33 exp 13,401,163; 34 expm1 13,401,163; 51 log 3,691,640; 64 log 235,537,138
- science/fluid/thermo/HydrocarbonModel.java: 53 exp 9,187
- science/fluid/thermo/TranslatedPengRobinson.java: 316 pow 26,727,940; 317 pow 26,727,940; 321 log 26,727,940
- science/fluid/transport/MixtureViscosity.java: 103 log 1,493,187; 127 exp 223,927
- science/fluid/transport/SlurryTransport.java: 27 pow 69,455
- science/material/ViscosityCorrelation.java: 95 exp 4,987,634; 95 log 14,962,902; 95 log 14,962,902; 95 log 14,962,902; 99 pow 3,790,876
- science/thermo/PengRobinson78.java: 126 log 9,187; 135 log 183,113; 194 cbrt 17,452; 194 cbrt 17,452; 203 acos 461; 206 cos 461; 207 cos 461; 208 cos 461
- science/thermo/PengRobinsonKernel.java: 271 log 26,727,940; 275 log 26,727,940; 339 log 31; 476 cbrt 22,237,506; 476 cbrt 22,237,506; 484 cbrt 162; 499 acos 15,609,187; 500 cos 15,609,187; 501 cos 15,609,187; 502 cos 15,609,187
Note: sites on the same line with the same function share one counter key (line:function), so their call count is printed once per site and is the combined count.

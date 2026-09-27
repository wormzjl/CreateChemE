| class | lines differing | max relative float difference | integer tokens changed | text changed |
|---|---|---|---|---|
| runtime.FluidPumpedFillLineTest | 2 | 0.34 (4.3299279573995715E-16 -> 2.858116906881357E-16) | 1 | 0 |
| runtime.fluid.DeadHeadedLineIslandTest | 1 | 8.01e-15 (709.7428590748111 -> 709.7428590748054) | 0 | 0 |
| runtime.fluid.ElevatedBlockLineIslandTest | 6 | 4.56e-09 (261.82467420693274 -> 261.8246730138809) | 0 | 0 |
| runtime.fluid.ExtremeTopologyIslandTest | 18 | 0.85 (8.88 -> 1.33) | 1 | 0 |
| runtime.fluid.FilterBlockLineIslandTest | 13 | 3.51e-11 (0.08138905819655261 -> 0.08138905819940638) | 0 | 0 |
| runtime.fluid.FullTankSolidsEventTest | 5 | 8.88e-15 (400000.0014512148 -> 400000.0014512183) | 0 | 0 |
| runtime.fluid.IslandCertificateTest | 11 | 1.63e-05 (2.6079021036296192E-9 -> 2.6079446519177548E-9) | 1 | 0 |
| runtime.fluid.LiquidJunctionTransientTest | 4 | 1.01 (-0.472 -> 0.00295) | 0 | 2: LIQUID_JUNCTION interval=0.1 tanks:0.05 m_J=9.774 kg Q(*time*)=2.740e-11 kg/s m_J/Q=3.567e+*time* tauFit(1.0-*time*)=Infinity s mismatch(*time*)=-0.427 mismatch => LIQUID_JUNCTION interval=0.1 tanks:0.05 m_J=9.774 kg Q(*time*)=1.562e-11 kg/s m_J/Q=6.258e+*time* tauFit(1.0-*time*)=Infinity s mismatch(*time*)=0.00294 mismatc |
| runtime.fluid.MixedGasJunctionTransientTest | 24 | 0.68 (7.288974828539274E-16 -> 2.3324719451325675E-16) | 0 | 0 |
| runtime.fluid.PhysicalFluidTopologyTest | 2 | 0.000671 (-2.168235369026661E-8 -> -2.1667801775038242E-8) | 0 | 0 |
| runtime.fluid.PhysicalRegistryTest | 1 | 3.15e-14 (0.9999999964953259 -> 0.9999999964952944) | 0 | 0 |
| runtime.fluid.TankOutletsTest | 3 | 2.25e-14 (33.108278002104264 -> 33.10827800210352) | 0 | 0 |
| science.column.v3.V3ColdStartSweepTest | 20 | 1 (4.144385457038879E-15 -> 3.301786155316222E-9) | 17 | 2: feed_k_545.0 | SUCCESS residual=4.755564662638153E-13 iterations=18 => feed_k_545.0 | NONCONVERGENCE residual=1.7262860061293883E-6 iterations=128 |
| science.column.v3.V3EnergyShiftPredictorTest | 1 | 4.76e-13 (0.014904539781864424 -> 0.014904539781857334) | 0 | 0 |
| science.column.v3.V3PumparoundCalculatorTest | 15 | 0.714 (1.0658141036401503E-14 -> 3.730349362740526E-14) | 0 | 0 |
| science.column.v3.V3PumparoundSteamCalculatorTest | 11 | 1.55 (-4.098e-08 -> 2.235e-08) | 0 | 0 |
| science.column.v3.V3StageContinuationTest | 7 | 3.3e-08 (0.001218359475471311 -> 0.001218359435238241) | 0 | 0 |
| science.column.v3.V3ThirtyStageColdStartTest | 1 | 0 () | 0 | 1: V3 30-stage cold outcome: Failure[code=LINEAR_SOLVE_FAILURE, summary=Stage continuation stalled at 4 stages after 1 Newton iterations; maximum scaled residual 1 => V3 30-stage cold outcome: Failure[code=NONCONVERGENCE, summary=Stage continuation stalled at 4 stages after 82 Newton iterations; maximum scaled residual 0.3176 |
| science.column.v3.V3TraceFloorSupportTest | 1 | 0.366 (2.909e-14 -> 1.843e-14) | 0 | 0 |
| science.fluid.network.CompressorTest | 5 | 1.67e-09 (0.0016237019842240586 -> 0.0016237019869415961) | 0 | 0 |
| science.fluid.network.InletPhaseTest | 4 | 6.2e-08 (9.249434149766665E-10 -> 9.249434723556447E-10) | 1 | 0 |
| science.fluid.network.JunctionWaterTraceTest | 6 | 0.312 (1.2885439272805073E-16 -> 1.873475052164422E-16) | 0 | 0 |
| science.fluid.network.LevelHeadTest | 26 | 0.00123 (4.292181069958848 -> 4.286885245901639) | 6 | 0 |
| science.fluid.network.PhasePortPriorityTest | 4695 | 0.75 (1.290219540337971E-16 -> 5.160878161351884E-16) | 12 | 1: PHASE_PORT_PRIORITY_RUN oil-water-overflow slices=80 accepted=279 rejected=26 worstRejectedPerSlice=16 reopens=1 reasons={Backward-Euler boundary reopened=1, Ba => PHASE_PORT_PRIORITY_RUN oil-water-overflow slices=80 accepted=279 rejected=26 worstRejectedPerSlice=16 reopens=1 reasons={Backward-Euler boundary reopened=1, Ba |
| science.fluid.network.PhasePortTest | 8 | 0.0727 (2.830275284390377E-16 -> 3.0522576596366815E-16) | 0 | 0 |
| science.fluid.network.VentGatePolishTest | 3 | 5.49e-15 (160.54206730645262 -> 160.5420673064535) | 0 | 0 |
| science.fluid.thermo.TranslatedPengRobinsonDerivativesTest | 10 | 0.906 (6.967e-16 -> 7.385e-15) | 0 | 1:     dv/dP                     max 2.174e-07 over 18 comparisons at T=300.0 P=101325.0 LIQUID (difference -1.90280057355e-13, analytic -1.90280098723e-13) =>     dv/dP                     max 5.547e-08 over 18 comparisons at T=520.0 P=250000.0 VAPOR (difference -9.56012772469e-08, analytic -9.56012719442e-08) |

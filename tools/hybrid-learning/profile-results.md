# Native profile and residual diagnostics

These deterministic prediction/property evaluations ran after all timed native campaigns. They contain no corrected requests and did not select models. All 405 validation inputs and all 252 test inputs remain included. Diagnostic budgets and costs are separate from the two-second measured neural budget.

Profile comparisons use the same 168 available original certified validation references across all eight pipelines, from the unchanged set of 168. No fresh-test teacher is chosen.

| Pipeline | Raw temperature RMSE K | Final temperature RMSE K | Raw phase-total RMSE / feed | Final phase-total RMSE / feed |
|---|---:|---:|---:|---:|
| incumbent | 11.3714 | 11.3714 | 0.138993 | 0.138993 |
| N-20260910 | 11.4279 | 11.4279 | 0.134011 | 0.155795 |
| Nplus1-20260910 | 11.5795 | 11.5795 | 0.129892 | 0.198502 |
| incumbent-wrapper | 11.3714 | 11.3714 | 0.138993 | 0.153094 |
| Nplus1-20260911 | 11.2298 | 11.2298 | 0.137642 | 0.152318 |
| N-20260911 | 11.1121 | 11.1121 | 0.133951 | 0.159497 |
| N-20260912 | 12.1209 | 12.1209 | 0.146102 | 0.161216 |
| Nplus1-20260912 | 11.7663 | 11.7663 | 0.135164 | 0.170725 |

| Population | Pipeline | Preparation status counts |
|---|---|---|
| validation | incumbent | DISABLED: 405 |
| validation | N-20260910 | INAPPLICABLE: 356, PREPARED: 14, DECLINED: 35 |
| validation | Nplus1-20260910 | INAPPLICABLE: 356, DECLINED: 32, PREPARED: 17 |
| validation | incumbent-wrapper | INAPPLICABLE: 356, PREPARED: 16, DECLINED: 33 |
| validation | Nplus1-20260911 | INAPPLICABLE: 356, PREPARED: 13, DECLINED: 36 |
| validation | N-20260911 | INAPPLICABLE: 356, DECLINED: 37, PREPARED: 12 |
| validation | N-20260912 | INAPPLICABLE: 356, PREPARED: 15, DECLINED: 34 |
| validation | Nplus1-20260912 | INAPPLICABLE: 356, PREPARED: 13, DECLINED: 36 |
| test | incumbent | DISABLED: 252 |
| test | Nplus1-20260910 | INAPPLICABLE: 215, DECLINED: 29, PREPARED: 8 |
| test | incumbent-wrapper | INAPPLICABLE: 215, DECLINED: 24, PREPARED: 13 |
| test | N-20260911 | INAPPLICABLE: 215, DECLINED: 29, PREPARED: 8 |

The JSON retains raw/final maxima separately for each native equation family, including physical and scaled values. Full-grid component-material defects use the original dry/no-side-draw formula only where applicable. Native residuals are evaluated after native trace/support projection and are not interchangeable with those full-grid defects. Support changes and residual-unavailable cases are retained.

Material completion retains temperature and does not enforce the energy or equilibrium equations. Its applicability and decline groups are descriptive subsets, not filtered benchmark populations or independently selected model candidates. Lower prediction error or residual magnitude is not a strict native success.

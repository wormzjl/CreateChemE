# V3 F06 failed 40% side-draw tray profile

Source: F06-dry-on, first truncated chain, attempt 8, final iterate after MAX_ITERATIONS (32). Maximum scaled residual: 0.2843202008642761. This is an unconverged numerical state, not a validated steady state.

Feed: 725.194444 mol/s at 638.15 K (365 C), tray 24. Reflux ratio: 2. Reboiler duty: 8 MW. Side draws: trays 8, 15, 22, totaling 40% of molar feed. Requested/applied cutoff on this attempt: 1e-6.

Vapor is upward flow leaving the tray. Gross liquid is the tray liquid outlet before side withdrawal. Net liquid is gross liquid minus the draw, flowing toward the next lower tray. Pressure is absolute, reconstructed from the authored pressure profile: P_j = 250 + 0.75*(j-1) kPa.

| Tray | T (C) | P (kPa abs) | Vapor (mol/s) | Gross liquid (mol/s) | Draw (mol/s) | Net liquid downward (mol/s) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 153.90 | 250.00 | 950.474 | 661.234 | 0.000 | 661.234 |
| 2 | 188.11 | 250.75 | 978.058 | 701.864 | 0.000 | 701.864 |
| 3 | 199.82 | 251.50 | 1018.688 | 717.693 | 0.000 | 717.693 |
| 4 | 204.48 | 252.25 | 1034.517 | 717.431 | 0.000 | 717.431 |
| 5 | 207.32 | 253.00 | 1034.256 | 703.546 | 0.000 | 703.546 |
| 6 | 210.85 | 253.75 | 1020.371 | 672.259 | 0.000 | 672.259 |
| 7 | 217.05 | 254.50 | 989.082 | 628.312 | 0.000 | 628.312 |
| 8 | 226.42 | 255.25 | 945.129 | 591.441 | 110.846 | 480.595 |
| 9 | 236.52 | 256.00 | 908.251 | 490.739 | 0.000 | 490.739 |
| 10 | 243.67 | 256.75 | 891.058 | 480.257 | 0.000 | 480.257 |
| 11 | 248.71 | 257.50 | 880.554 | 462.891 | 0.000 | 462.891 |
| 12 | 253.99 | 258.25 | 863.161 | 431.805 | 0.000 | 431.805 |
| 13 | 261.73 | 259.00 | 832.001 | 387.147 | 0.000 | 387.147 |
| 14 | 273.25 | 259.75 | 787.194 | 329.565 | 0.000 | 329.565 |
| 15 | 289.92 | 260.50 | 729.374 | 262.882 | 145.933 | 116.949 |
| 16 | 312.69 | 261.25 | 662.394 | 118.903 | 0.000 | 118.903 |
| 17 | 327.44 | 262.00 | 628.167 | 107.552 | 0.000 | 107.552 |
| 18 | 333.92 | 262.75 | 615.815 | 101.543 | 0.000 | 101.543 |
| 19 | 336.77 | 263.50 | 609.425 | 95.642 | 0.000 | 95.642 |
| 20 | 338.86 | 264.25 | 603.404 | 86.005 | 0.000 | 86.005 |
| 21 | 342.07 | 265.00 | 593.566 | 69.268 | 0.000 | 69.268 |
| 22 | 347.87 | 265.75 | 576.434 | 32.769 | 33.299 | -0.530 |
| 23 | 362.76 | 266.50 | 539.168 | 0.066 | 0.000 | 0.066 |
| 24 | 365.43 | 267.25 | 531.292 | 196.304 | 0.000 | 196.304 |
| 25 | 365.45 | 268.00 | 6.129 | 196.448 | 0.000 | 196.448 |
| 26 | 365.48 | 268.75 | 6.273 | 196.608 | 0.000 | 196.608 |
| 27 | 365.52 | 269.50 | 6.434 | 196.817 | 0.000 | 196.817 |
| 28 | 365.60 | 270.25 | 6.643 | 197.168 | 0.000 | 197.168 |
| 29 | 365.84 | 271.00 | 6.994 | 198.058 | 0.000 | 198.058 |
| 30 | 368.41 | 271.75 | 7.884 | 202.487 | 0.000 | 202.487 |

Tray 22 has negative net liquid: -0.5300835122165779 mol/s. Tray 23 has only 0.06632326147006087 mol/s liquid. These are features of the failed iterate.

Condenser: 50 C, 250 kPa absolute, vapor 0 mol/s, gross condensed liquid 950.473858312 mol/s, reflux 633.649238874 mol/s, distillate 316.824619437 mol/s.
Reboiler: 412.911224 C, 271.75 kPa absolute, upward vapor 12.315219887 mol/s, liquid bottoms 190.175386972 mol/s.

Full-precision source, including component flow vectors and all Newton diagnostics: [attempt-008.json](D:/Minecraft/Modding/1.21/CreateChemE/build/v3-truncation-investigation-20260905/results/traced-F06-dry-on-trace/attempt-008.json).

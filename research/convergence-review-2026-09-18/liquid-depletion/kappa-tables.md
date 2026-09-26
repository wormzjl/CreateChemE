### K1 Outcome by kappa_req band (draw requests with a known withdrawal)

| scope | kappa_req band | n | success rate | hard W>=1 | hard rate | family | family rate | near band and solved | near-solved rate |
|---|---|---|---|---|---|---|---|---|---|
| design-v2 | 0 | 1092 | 51.3% | 153 | 14.0% | 183 | 16.8% | 10 | 0.9% |
| design-v2 | (0,0.25] | 45 | 48.9% | 9 | 20.0% | 11 | 24.4% | 0 | 0.0% |
| design-v2 | (0.25,0.5] | 115 | 54.8% | 17 | 14.8% | 17 | 14.8% | 2 | 1.7% |
| design-v2 | (0.5,1] | 281 | 68.0% | 20 | 7.1% | 26 | 9.3% | 5 | 1.8% |
| design-v2 | (1,2] | 615 | 75.8% | 17 | 2.8% | 24 | 3.9% | 3 | 0.5% |
| design-v2 | (2,4] | 158 | 63.9% | 5 | 3.2% | 8 | 5.1% | 1 | 0.6% |
| design-v2 | >4 | 58 | 63.8% | 0 | 0.0% | 0 | 0.0% | 0 | 0.0% |
| design-g | 0 | 433 | 52.0% | 49 | 11.3% | 63 | 14.5% | 6 | 1.4% |
| design-g | (0,0.25] | 27 | 40.7% | 2 | 7.4% | 2 | 7.4% | 2 | 7.4% |
| design-g | (0.25,0.5] | 40 | 70.0% | 2 | 5.0% | 3 | 7.5% | 1 | 2.5% |
| design-g | (0.5,1] | 101 | 69.3% | 8 | 7.9% | 13 | 12.9% | 4 | 4.0% |
| design-g | (1,2] | 246 | 75.6% | 6 | 2.4% | 8 | 3.3% | 2 | 0.8% |
| design-g | (2,4] | 63 | 69.8% | 1 | 1.6% | 2 | 3.2% | 0 | 0.0% |
| design-g | >4 | 30 | 76.7% | 0 | 0.0% | 0 | 0.0% | 0 | 0.0% |
| codex | 0 | 165 | 49.1% | 30 | 18.2% | 36 | 21.8% | 4 | 2.4% |
| codex | (0,0.25] | 15 | 60.0% | 1 | 6.7% | 1 | 6.7% | 1 | 6.7% |
| codex | (0.25,0.5] | 12 | 75.0% | 1 | 8.3% | 1 | 8.3% | 1 | 8.3% |
| codex | (0.5,1] | 29 | 65.5% | 3 | 10.3% | 3 | 10.3% | 2 | 6.9% |
| codex | (1,2] | 46 | 78.3% | 5 | 10.9% | 5 | 10.9% | 1 | 2.2% |
| codex | (2,4] | 34 | 61.8% | 0 | 0.0% | 0 | 0.0% | 0 | 0.0% |
| codex | >4 | 7 | 71.4% | 0 | 0.0% | 0 | 0.0% | 0 | 0.0% |
| pooled | 0 | 1690 | 51.2% | 232 | 13.7% | 282 | 16.7% | 20 | 1.2% |
| pooled | (0,0.25] | 87 | 48.3% | 12 | 13.8% | 14 | 16.1% | 3 | 3.4% |
| pooled | (0.25,0.5] | 167 | 59.9% | 20 | 12.0% | 21 | 12.6% | 4 | 2.4% |
| pooled | (0.5,1] | 411 | 68.1% | 31 | 7.5% | 42 | 10.2% | 11 | 2.7% |
| pooled | (1,2] | 907 | 75.9% | 28 | 3.1% | 37 | 4.1% | 6 | 0.7% |
| pooled | (2,4] | 255 | 65.1% | 6 | 2.4% | 10 | 3.9% | 1 | 0.4% |
| pooled | >4 | 95 | 68.4% | 0 | 0.0% | 0 | 0.0% | 0 | 0.0% |

### K2 Outcome by structural coverage flag

| scope | flag | value | n | success rate | hard W>=1 | hard rate | family | family rate | near band and solved | near-solved rate |
|---|---|---|---|---|---|---|---|---|---|---|
| design-v2 | every draw tray covered by a zone above it | True | 692 | 72.3% | 19 | 2.7% | 26 | 3.8% | 2 | 0.3% |
| design-v2 | every draw tray covered by a zone above it | False | 1672 | 56.2% | 202 | 12.1% | 243 | 14.5% | 19 | 1.1% |
| design-v2 | at least one draw tray covered | True | 1308 | 67.4% | 95 | 7.3% | 119 | 9.1% | 13 | 1.0% |
| design-v2 | at least one draw tray covered | False | 1056 | 52.9% | 126 | 11.9% | 150 | 14.2% | 8 | 0.8% |
| design-v2 | a zone ends at or above the shallowest draw | True | 692 | 72.3% | 19 | 2.7% | 26 | 3.8% | 2 | 0.3% |
| design-v2 | a zone ends at or above the shallowest draw | False | 1672 | 56.2% | 202 | 12.1% | 243 | 14.5% | 19 | 1.1% |
| design-g | every draw tray covered by a zone above it | True | 287 | 73.2% | 8 | 2.8% | 12 | 4.2% | 3 | 1.0% |
| design-g | every draw tray covered by a zone above it | False | 653 | 57.7% | 60 | 9.2% | 79 | 12.1% | 12 | 1.8% |
| design-g | at least one draw tray covered | True | 528 | 68.4% | 28 | 5.3% | 37 | 7.0% | 8 | 1.5% |
| design-g | at least one draw tray covered | False | 412 | 54.9% | 40 | 9.7% | 54 | 13.1% | 7 | 1.7% |
| design-g | a zone ends at or above the shallowest draw | True | 287 | 73.2% | 8 | 2.8% | 12 | 4.2% | 3 | 1.0% |
| design-g | a zone ends at or above the shallowest draw | False | 653 | 57.7% | 60 | 9.2% | 79 | 12.1% | 12 | 1.8% |
| codex | every draw tray covered by a zone above it | True | 68 | 72.1% | 3 | 4.4% | 3 | 4.4% | 3 | 4.4% |
| codex | every draw tray covered by a zone above it | False | 240 | 54.6% | 37 | 15.4% | 43 | 17.9% | 6 | 2.5% |
| codex | at least one draw tray covered | True | 154 | 68.2% | 19 | 12.3% | 19 | 12.3% | 7 | 4.5% |
| codex | at least one draw tray covered | False | 154 | 48.7% | 21 | 13.6% | 27 | 17.5% | 2 | 1.3% |
| codex | a zone ends at or above the shallowest draw | True | 68 | 72.1% | 3 | 4.4% | 3 | 4.4% | 3 | 4.4% |
| codex | a zone ends at or above the shallowest draw | False | 240 | 54.6% | 37 | 15.4% | 43 | 17.9% | 6 | 2.5% |
| pooled | every draw tray covered by a zone above it | True | 1047 | 72.5% | 30 | 2.9% | 41 | 3.9% | 8 | 0.8% |
| pooled | every draw tray covered by a zone above it | False | 2565 | 56.5% | 299 | 11.7% | 365 | 14.2% | 37 | 1.4% |
| pooled | at least one draw tray covered | True | 1990 | 67.7% | 142 | 7.1% | 175 | 8.8% | 28 | 1.4% |
| pooled | at least one draw tray covered | False | 1622 | 53.0% | 187 | 11.5% | 231 | 14.2% | 17 | 1.0% |
| pooled | a zone ends at or above the shallowest draw | True | 1047 | 72.5% | 30 | 2.9% | 41 | 3.9% | 8 | 0.8% |
| pooled | a zone ends at or above the shallowest draw | False | 2565 | 56.5% | 299 | 11.7% | 365 | 14.2% | 37 | 1.4% |

### K3 Screen view: kappa_req thresholds against, and combined with, sigma >= 0.66

| rule | scope | fired | hard hits | hard recall | near-fail hits | near recall | false positives on solved | FP rate | residual family |
|---|---|---|---|---|---|---|---|---|---|
| sigma >= 0.66 | design-v2 | 111 | 47/221 | 21.3% | 7/48 | 14.6% | 10/1440 | 0.7% | 215 |
| sigma >= 0.66 | design-g | 38 | 19/68 | 27.9% | 3/23 | 13.0% | 1/587 | 0.2% | 69 |
| sigma >= 0.66 | codex | 15 | 8/40 | 20.0% | 1/6 | 16.7% | 0/180 | 0.0% | 37 |
| sigma >= 0.66 | pooled | 164 | 74/329 | 22.5% | 11/77 | 14.3% | 11/2207 | 0.5% | 321 |
| kappa_req < 0.25 | design-v2 | 1137 | 162/221 | 73.3% | 32/48 | 66.7% | 582/1440 | 40.4% | 75 |
| kappa_req < 0.25 | design-g | 460 | 51/68 | 75.0% | 14/23 | 60.9% | 236/587 | 40.2% | 26 |
| kappa_req < 0.25 | codex | 180 | 31/40 | 77.5% | 6/6 | 100.0% | 90/180 | 50.0% | 9 |
| kappa_req < 0.25 | pooled | 1777 | 244/329 | 74.2% | 52/77 | 67.5% | 908/2207 | 41.1% | 110 |
| sigma >= 0.66 OR kappa_req < 0.25 | design-v2 | 1141 | 164/221 | 74.2% | 33/48 | 68.8% | 582/1440 | 40.4% | 72 |
| sigma >= 0.66 OR kappa_req < 0.25 | design-g | 461 | 52/68 | 76.5% | 14/23 | 60.9% | 236/587 | 40.2% | 25 |
| sigma >= 0.66 OR kappa_req < 0.25 | codex | 180 | 31/40 | 77.5% | 6/6 | 100.0% | 90/180 | 50.0% | 9 |
| sigma >= 0.66 OR kappa_req < 0.25 | pooled | 1782 | 247/329 | 75.1% | 53/77 | 68.8% | 908/2207 | 41.1% | 106 |
| kappa_req < 0.5 | design-v2 | 1252 | 179/221 | 81.0% | 32/48 | 66.7% | 645/1440 | 44.8% | 58 |
| kappa_req < 0.5 | design-g | 500 | 53/68 | 77.9% | 15/23 | 65.2% | 264/587 | 45.0% | 23 |
| kappa_req < 0.5 | codex | 192 | 32/40 | 80.0% | 6/6 | 100.0% | 99/180 | 55.0% | 8 |
| kappa_req < 0.5 | pooled | 1944 | 264/329 | 80.2% | 53/77 | 68.8% | 1008/2207 | 45.7% | 89 |
| sigma >= 0.66 OR kappa_req < 0.5 | design-v2 | 1254 | 180/221 | 81.4% | 33/48 | 68.8% | 645/1440 | 44.8% | 56 |
| sigma >= 0.66 OR kappa_req < 0.5 | design-g | 501 | 54/68 | 79.4% | 15/23 | 65.2% | 264/587 | 45.0% | 22 |
| sigma >= 0.66 OR kappa_req < 0.5 | codex | 192 | 32/40 | 80.0% | 6/6 | 100.0% | 99/180 | 55.0% | 8 |
| sigma >= 0.66 OR kappa_req < 0.5 | pooled | 1947 | 266/329 | 80.9% | 54/77 | 70.1% | 1008/2207 | 45.7% | 86 |
| kappa_req < 1 | design-v2 | 1533 | 199/221 | 90.0% | 38/48 | 79.2% | 836/1440 | 58.1% | 32 |
| kappa_req < 1 | design-g | 601 | 61/68 | 89.7% | 20/23 | 87.0% | 334/587 | 56.9% | 10 |
| kappa_req < 1 | codex | 221 | 35/40 | 87.5% | 6/6 | 100.0% | 118/180 | 65.6% | 5 |
| kappa_req < 1 | pooled | 2355 | 295/329 | 89.7% | 64/77 | 83.1% | 1288/2207 | 58.4% | 47 |
| sigma >= 0.66 OR kappa_req < 1 | design-v2 | 1533 | 199/221 | 90.0% | 38/48 | 79.2% | 836/1440 | 58.1% | 32 |
| sigma >= 0.66 OR kappa_req < 1 | design-g | 601 | 61/68 | 89.7% | 20/23 | 87.0% | 334/587 | 56.9% | 10 |
| sigma >= 0.66 OR kappa_req < 1 | codex | 221 | 35/40 | 87.5% | 6/6 | 100.0% | 118/180 | 65.6% | 5 |
| sigma >= 0.66 OR kappa_req < 1 | pooled | 2355 | 295/329 | 89.7% | 64/77 | 83.1% | 1288/2207 | 58.4% | 47 |
| kappa_req < 2 | design-v2 | 2148 | 216/221 | 97.7% | 45/48 | 93.8% | 1302/1440 | 90.4% | 8 |
| kappa_req < 2 | design-g | 847 | 67/68 | 98.5% | 22/23 | 95.7% | 520/587 | 88.6% | 2 |
| kappa_req < 2 | codex | 267 | 40/40 | 100.0% | 6/6 | 100.0% | 154/180 | 85.6% | 0 |
| kappa_req < 2 | pooled | 3262 | 323/329 | 98.2% | 73/77 | 94.8% | 1976/2207 | 89.5% | 10 |
| sigma >= 0.66 OR kappa_req < 2 | design-v2 | 2148 | 216/221 | 97.7% | 45/48 | 93.8% | 1302/1440 | 90.4% | 8 |
| sigma >= 0.66 OR kappa_req < 2 | design-g | 847 | 67/68 | 98.5% | 22/23 | 95.7% | 520/587 | 88.6% | 2 |
| sigma >= 0.66 OR kappa_req < 2 | codex | 267 | 40/40 | 100.0% | 6/6 | 100.0% | 154/180 | 85.6% | 0 |
| sigma >= 0.66 OR kappa_req < 2 | pooled | 3262 | 323/329 | 98.2% | 73/77 | 94.8% | 1976/2207 | 89.5% | 10 |
| sigma >= 0.66 AND kappa_req < 1 | design-v2 | 111 | 47/221 | 21.3% | 7/48 | 14.6% | 10/1440 | 0.7% | 215 |
| sigma >= 0.66 AND kappa_req < 1 | design-g | 38 | 19/68 | 27.9% | 3/23 | 13.0% | 1/587 | 0.2% | 69 |
| sigma >= 0.66 AND kappa_req < 1 | codex | 15 | 8/40 | 20.0% | 1/6 | 16.7% | 0/180 | 0.0% | 37 |
| sigma >= 0.66 AND kappa_req < 1 | pooled | 164 | 74/329 | 22.5% | 11/77 | 14.3% | 11/2207 | 0.5% | 321 |

### K4 kappa_req distribution by outcome (pooled)

| outcome | n | share with kappa_req = 0 | p05 | p25 | p50 | p75 | p95 |
|---|---|---|---|---|---|---|---|
| hard | 329 | 70.5% | 0 | 0 | 0 | 0.274 | 1.53 |
| nearfail | 77 | 64.9% | 0 | 0 | 0 | 0.662 | 1.95 |
| nearsolved | 45 | 44.4% | 0 | 0 | 0.174 | 0.844 | 1.76 |
| solved | 2162 | 39.1% | 0 | 0 | 0.709 | 1.48 | 3.26 |

### K5 Family rate on the sigma x kappa_req grid (pooled)

| sigma band | kappa_req band | n | success rate | hard | hard rate | family | family rate |
|---|---|---|---|---|---|---|---|
| sigma < 0.45 | kappa_req = 0 | 1085 | 63.3% | 80 | 7.4% | 96 | 8.8% |
| sigma < 0.45 | 0 < kappa_req <= 1 | 596 | 69.1% | 38 | 6.4% | 50 | 8.4% |
| sigma < 0.45 | kappa_req > 1 | 1246 | 73.7% | 32 | 2.6% | 44 | 3.5% |
| 0.45 <= sigma < 0.66 | kappa_req = 0 | 450 | 37.3% | 82 | 18.2% | 107 | 23.8% |
| 0.45 <= sigma < 0.66 | 0 < kappa_req <= 1 | 60 | 16.7% | 21 | 35.0% | 21 | 35.0% |
| 0.45 <= sigma < 0.66 | kappa_req > 1 | 11 | 9.1% | 2 | 18.2% | 3 | 27.3% |
| sigma >= 0.66 | kappa_req = 0 | 155 | 7.1% | 70 | 45.2% | 79 | 51.0% |
| sigma >= 0.66 | 0 < kappa_req <= 1 | 9 | 0.0% | 4 | 44.4% | 6 | 66.7% |

### K6 kappa_req and outcome by the shallowest authored draw tray (pooled)

| shallowest draw | n | median kappa_req | share kappa_req = 0 | success rate | family rate |
|---|---|---|---|---|---|
| tray 1 | 289 | 0 | 83.7% | 59.9% | 17.3% |
| trays 2-3 | 438 | 0 | 76.9% | 57.5% | 17.6% |
| trays 4-10 | 1417 | 0.433 | 43.3% | 63.5% | 11.1% |
| tray >10 | 1468 | 0.989 | 33.9% | 60.1% | 8.3% |

### K7 Cooling coverage inside and outside the hot-condenser corner (pooled)

| condenser half | kappa_req band | n | success rate | hard | hard rate | family rate |
|---|---|---|---|---|---|---|
| condenser T below median (298-340 K) | kappa_req <= 1 | 1076 | 66.8% | 75 | 7.0% | 8.8% |
| condenser T below median (298-340 K) | kappa_req > 1 | 730 | 78.5% | 7 | 1.0% | 1.6% |
| condenser T above median (340-399 K) | kappa_req <= 1 | 1279 | 44.5% | 220 | 17.2% | 20.6% |
| condenser T above median (340-399 K) | kappa_req > 1 | 527 | 65.7% | 27 | 5.1% | 6.6% |

### K8 The counterweight: heat gating and unmeasurable outcomes by kappa_req band, over ALL draw requests (pooled)

| kappa_req band | n | heat-gated | heat-gate rate | unknown W | unknown rate | success rate | family | family rate |
|---|---|---|---|---|---|---|---|---|
| 0 | 2923 | 592 | 20.3% | 1233 | 42.2% | 29.6% | 282 | 9.6% |
| (0,0.25] | 199 | 62 | 31.2% | 112 | 56.3% | 21.1% | 14 | 7.0% |
| (0.25,0.5] | 326 | 104 | 31.9% | 159 | 48.8% | 30.7% | 21 | 6.4% |
| (0.5,1] | 717 | 159 | 22.2% | 306 | 42.7% | 39.1% | 42 | 5.9% |
| (1,2] | 1428 | 238 | 16.7% | 521 | 36.5% | 48.2% | 37 | 2.6% |
| (2,4] | 612 | 223 | 36.4% | 357 | 58.3% | 27.1% | 10 | 1.6% |
| >4 | 247 | 90 | 36.4% | 152 | 61.5% | 26.3% | 0 | 0.0% |

### K10 Family rate by kappa_req with the pumparound count held fixed (pooled, known W)

| pumparounds | kappa_req band | n | success rate | hard | hard rate | family | family rate |
|---|---|---|---|---|---|---|---|
| 1 | kappa_req = 0 | 382 | 48.7% | 52 | 13.6% | 60 | 15.7% |
| 1 | 0 < kappa_req <= 1 | 208 | 60.1% | 19 | 9.1% | 25 | 12.0% |
| 1 | kappa_req > 1 | 121 | 79.3% | 4 | 3.3% | 6 | 5.0% |
| 2 | kappa_req = 0 | 198 | 47.0% | 41 | 20.7% | 46 | 23.2% |
| 2 | 0 < kappa_req <= 1 | 114 | 55.3% | 9 | 7.9% | 12 | 10.5% |
| 2 | kappa_req > 1 | 218 | 73.9% | 8 | 3.7% | 10 | 4.6% |
| 3 | kappa_req = 0 | 123 | 53.7% | 28 | 22.8% | 29 | 23.6% |
| 3 | 0 < kappa_req <= 1 | 260 | 71.9% | 19 | 7.3% | 22 | 8.5% |
| 3 | kappa_req > 1 | 740 | 75.1% | 14 | 1.9% | 20 | 2.7% |
| 4 | kappa_req = 0 | 103 | 44.7% | 15 | 14.6% | 16 | 15.5% |
| 4 | 0 < kappa_req <= 1 | 83 | 56.6% | 16 | 19.3% | 18 | 21.7% |
| 4 | kappa_req > 1 | 178 | 59.6% | 8 | 4.5% | 11 | 6.2% |

### K9 Feasibility of the proposed constraint on the existing matrices

| scope | draw requests | no cooling pumparound at all | share | a draw on tray 1 | share | already meets kappa_req >= 1 | share |
|---|---|---|---|---|---|---|---|
| design-v2 | 4200 | 720 | 17.1% | 404 | 9.6% | 1500 | 35.7% |
| design-g | 1680 | 288 | 17.1% | 157 | 9.3% | 616 | 36.7% |
| codex | 572 | 111 | 19.4% | 79 | 13.8% | 171 | 29.9% |
| pooled | 6452 | 1119 | 17.3% | 640 | 9.9% | 2287 | 35.4% |


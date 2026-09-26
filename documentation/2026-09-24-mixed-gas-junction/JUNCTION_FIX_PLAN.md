# Proposed mixed-junction repair plan
Planned, 2026-09-25. Investigation concluded; implementation not merged.

1. Implement a coherent hydraulic-pressure / incoming-mixture / enthalpy seed for cold multiport junctions. Keep zero-storage semantics and preserve valid warm guesses.
2. Isolate and repair the near-zero-flow mixture Jacobian and stagnant/donor transition, including one-way boundary shutoff and velocity-cap transitions.
3. Add assertion-based reproduction and finite-tank tests; require 20 mm and 50 mm 4/5/6-port runs to pass through rest and restart, with direction reversal, conservation and timing/accuracy checks.
4. Broaden to adjacent junctions, finite reservoir boundaries, changed inputs, vertical head, filters, pumps, phase changes and property-domain bounds. Profile bounded initializer cost, then run the unchanged gates.
5. Review before merging. No production fix has been approved by these partial prototype results.

See JUNCTION_REVIEW.md for confirmed startup cause, remaining transient failure, exact experiments and detached prototype locations.

import json
E=[["""    /**
     * Where the outflow throttle of an open phase port lands its phase ({@code phi_reserve}; decision A4): the port may
     * draw at most {@code (phi_start - phi_reserve) V rho_stream,start / (n dt)} over a step (see {@link Equations#throttles}).
     * The gap to {@link #PHASE_PORT_OPEN} is the band: a port drained to the reserve cannot reopen until its phase has
     * regrown by half a percent of the vessel, so it cannot open and close on alternate steps by itself, and the band needs
     * no carried state (plan 3.4; documentation/2026-09-26-phase-ports-and-compressor/PHASE_PORTS_REVIEW.md, WP2).
     */""","""    /**
     * The floor of an open phase port's outflow throttle ({@code phi_reserve}; decision A4): the port may draw at most
     * {@code (phi_start - phi_reserve) V rho_stream,start / (n dt)} over a step (see {@link Equations#throttles}), so no step
     * draws its phase below the reserve by more than the flash moves the interface. Where one step spans the band - a draw
     * the throttle cuts - the phase lands on the reserve and the gap to {@link #PHASE_PORT_OPEN} keeps the port shut until
     * it regrows by half a percent of the vessel. Under the state-change controller's steps a draining port usually closes
     * at the first step start below {@code phi_open} instead (0.99 % on the drained water tank), and a port fed while it
     * drains duty-cycles about {@code phi_open} (risk R1): stateless, no carried state (plan 3.4;
     * documentation/2026-09-26-phase-ports-and-compressor/PHASE_PORTS_REVIEW.md, WP2).
     */"""]]
json.dump(E,open("/tmp/claude-0/-home-user-CreateChemE/cfcc6b94-4f24-5f12-ba62-46e9aaaec421/scratchpad/wp2/e4.json","w"))

package com.wormzjl.createcheme.science.fluid.network;

/** Immutable actuator settings; the active regime belongs to a solve attempt, never to this shared value. */
public sealed interface FlowControl permits FlowControl.Passive,FlowControl.Mover,FlowControl.PressureValve {
    record Passive() implements FlowControl {}
    /**
     * A device that moves fluid from its connection's first end (its suction) to its second at a set suction volume flow,
     * up to a pressure-rise limit: the liquid pump and the gas compressor (decisions D1, D2, D7, D8 of
     * documentation/2026-09-26-phase-ports-and-compressor). The solver runs both on one set of modes
     * ({@code PUMP_TARGET}, {@code PUMP_HEAD_LIMIT}, {@code CLOSED}, {@code PUMP_VELOCITY_LIMIT},
     * {@code INLET_WRONG_PHASE}), rows, shutoff band and carry; they differ only in the rise limit, the shaft work and the
     * inlet phase they admit. Every quantity reads the suction only (decision D4): the stream the first end draws, and the
     * first end's pressure.
     */
    sealed interface Mover extends FlowControl permits Pump,Compressor {
        /** Suction volume flow target (m3/s at suction conditions). */
        double targetVolumeFlow();
        /** Shaft efficiency, in (0, 1]. */
        double efficiency();
        /**
         * The largest pressure rise (Pa) the device can add over what its suction draws: {@code suctionDensity} (kg/m3) is
         * the density of the stream the first end draws, {@code suctionPressure} (Pa) the first end's driving pressure,
         * {@code referenceDensity} the pump setting's reference fluid density
         * ({@link com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics#pumpReferenceDensity()}).
         */
        double riseLimit(double suctionDensity,double suctionPressure,double referenceDensity);
        /**
         * The shaft power (W) the device puts into what it moves, booked as heat into the discharge: {@code massFlow}
         * (kg/s, forward), {@code head} (Pa, the rise the device holds), both clipped at zero, and the suction's density
         * and pressure only.
         */
        double power(double massFlow,double head,double suctionDensity,double suctionPressure);
        /**
         * Whether a supply (what the suction line delivers, {@link InletPhase.Supply}) is refused for the next slice
         * ({@code INLET_WRONG_PHASE}), given whether the device stood refused at the committed slice end: a refusal
         * threshold, and a lower resume threshold the measure must fall below before a refused device runs again.
         */
        boolean refuses(InletPhase.Supply supply,boolean refused);
        /** The device's name in reasons ("pump", "compressor"). */
        String deviceName();
    }
    /** Suction volumetric target (m3/s), maximum added pressure (Pa), hydraulic efficiency. A liquid pump (decision D1):
     * it refuses a supply whose vapour fills more than {@link #VAPOUR_REFUSE} of its fluid volume, and runs again once
     * that share is below {@link #VAPOUR_RESUME}. */
    record Pump(double targetVolumeFlow,double maximumAddedPressure,double efficiency) implements Mover {
        /** Vapour share of the supply's fluid volume above which a pump refuses (plan Appendix B, decision D6). */
        public static final double VAPOUR_REFUSE=.02;
        /** Vapour share below which a refused pump runs again. */
        public static final double VAPOUR_RESUME=.005;
        public Pump {
            if(!Double.isFinite(targetVolumeFlow)||targetVolumeFlow<0||!Double.isFinite(maximumAddedPressure)||maximumAddedPressure<=0
                    ||!Double.isFinite(efficiency)||efficiency<=0||efficiency>1)throw new IllegalArgumentException("Invalid pump settings");
        }
        /** The setting is the rise for the reference fluid (water at 298.15 K, 1 atm), scaled by the suction's density: a
         * head, the one quantity a real pump fixes (decision D4: suction density only). */
        @Override public double riseLimit(double suctionDensity,double suctionPressure,double referenceDensity) {
            return maximumAddedPressure*suctionDensity/referenceDensity;
        }
        /** {@code q v_s head / eff}: the hydraulic power on the suction's specific volume. */
        @Override public double power(double massFlow,double head,double suctionDensity,double suctionPressure) {
            return Math.max(0,massFlow)/suctionDensity*Math.max(0,head)/efficiency;
        }
        @Override public boolean refuses(InletPhase.Supply supply,boolean refused) {
            double share=supply.vapourVolumeShare();
            return share>VAPOUR_REFUSE||refused&&!(share<VAPOUR_RESUME);
        }
        @Override public String deviceName(){return "pump";}
    }
    /**
     * A gas compressor (decisions D2, D7, D8): suction volume flow target (m3/s at inlet conditions), maximum pressure
     * ratio {@code r_max} (discharge over suction), efficiency. Its rise limit is {@code (r_max - 1) P_suction}; its shaft
     * work the ideal isothermal work on suction properties over the efficiency, booked as heat into the discharge. It
     * refuses a supply whose condensed phases (hydrocarbon liquid, free water and solids) exceed
     * {@link #CONDENSED_REFUSE} of its mass, or that carries any solid population above
     * {@link com.wormzjl.createcheme.science.fluid.transport.SlurryTransport#DEFAULT_TRACE_VOLUME_FRACTION} of its volume,
     * and runs again once the condensed share is below {@link #CONDENSED_RESUME} (and no solid is above the trace).
     */
    record Compressor(double targetVolumeFlow,double maximumPressureRatio,double efficiency) implements Mover {
        /** Condensed share of the supply's mass above which a compressor refuses (plan Appendix B, decision D6). */
        public static final double CONDENSED_REFUSE=.01;
        /** Condensed share below which a refused compressor runs again. */
        public static final double CONDENSED_RESUME=.002;
        public Compressor {
            if(!Double.isFinite(targetVolumeFlow)||targetVolumeFlow<0||!Double.isFinite(maximumPressureRatio)||maximumPressureRatio<=1
                    ||!Double.isFinite(efficiency)||efficiency<=0||efficiency>1)throw new IllegalArgumentException("Invalid compressor settings");
        }
        /** {@code (r_max - 1) P_suction} (decision D7): the discharge stands at most {@code r_max} times the suction. */
        @Override public double riseLimit(double suctionDensity,double suctionPressure,double referenceDensity) {
            return (maximumPressureRatio-1)*suctionPressure;
        }
        /** {@code q (P_s / rho_s) ln(1 + head / P_s) / eff} (decision D8): the ideal isothermal work of raising the suction
         * stream by {@code head}, on suction properties only. */
        @Override public double power(double massFlow,double head,double suctionDensity,double suctionPressure) {
            return Math.max(0,massFlow)*(suctionPressure/suctionDensity)*Math.log1p(Math.max(0,head)/suctionPressure)/efficiency;
        }
        @Override public boolean refuses(InletPhase.Supply supply,boolean refused) {
            if(supply.largestSolidVolumeFraction()>com.wormzjl.createcheme.science.fluid.transport.SlurryTransport.DEFAULT_TRACE_VOLUME_FRACTION)return true;
            double share=supply.condensedMassShare();
            return share>CONDENSED_REFUSE||refused&&!(share<CONDENSED_RESUME);
        }
        @Override public String deviceName(){return "compressor";}
    }
    /** Sustains upstream absolute pressure; reverse flow is closed. */
    record PressureValve(double targetPressure) implements FlowControl {
        public PressureValve {if(!Double.isFinite(targetPressure)||targetPressure<=0)throw new IllegalArgumentException("Invalid valve target");}
    }
    /**
     * The regime of one connection in a solve. {@code INLET_WRONG_PHASE} (decision D6) is a mover refused for a whole
     * slice because its supply is the wrong phase ({@link InletPhase}): in the solver it is {@code CLOSED} (flow exactly
     * zero, never reopened within the slice), and it persists as an endpoint mode so the next slice's hysteresis reads it.
     * Appended last: endpoint modes are saved as ordinals.
     */
    enum Mode { PASSIVE, PUMP_TARGET, PUMP_HEAD_LIMIT, VALVE_REGULATING, VALVE_OPEN, CLOSED,
        VELOCITY_LIMITED, PUMP_VELOCITY_LIMIT, VALVE_VELOCITY_LIMIT, INLET_WRONG_PHASE }
}

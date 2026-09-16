package com.wormzjl.createcheme.science.fluid.network;

/** Immutable actuator settings; the active regime belongs to a solve attempt, never to this shared value. */
public sealed interface FlowControl permits FlowControl.Passive,FlowControl.Pump,FlowControl.PressureValve {
    record Passive() implements FlowControl {}
    /** Suction volumetric target (m3/s), maximum added pressure (Pa), hydraulic efficiency. */
    record Pump(double targetVolumeFlow,double maximumAddedPressure,double efficiency) implements FlowControl {
        public Pump {
            if(!Double.isFinite(targetVolumeFlow)||targetVolumeFlow<0||!Double.isFinite(maximumAddedPressure)||maximumAddedPressure<=0
                    ||!Double.isFinite(efficiency)||efficiency<=0||efficiency>1)throw new IllegalArgumentException("Invalid pump settings");
        }
    }
    /** Sustains upstream absolute pressure; reverse flow is closed. */
    record PressureValve(double targetPressure) implements FlowControl {
        public PressureValve {if(!Double.isFinite(targetPressure)||targetPressure<=0)throw new IllegalArgumentException("Invalid valve target");}
    }
    enum Mode { PASSIVE, PUMP_TARGET, PUMP_HEAD_LIMIT, VALVE_REGULATING, VALVE_OPEN, CLOSED,
        VELOCITY_LIMITED, PUMP_VELOCITY_LIMIT, VALVE_VELOCITY_LIMIT }
}

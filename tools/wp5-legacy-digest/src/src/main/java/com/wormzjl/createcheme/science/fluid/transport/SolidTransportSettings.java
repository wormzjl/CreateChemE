package com.wormzjl.createcheme.science.fluid.transport;

/** Captured transport settings; changing them never changes a material or energy basis. */
public record SolidTransportSettings(double immobileViscosity,double traceVolumeFraction,double suspensionMultiplier,
                                     double filterCapacity,double filterResistance) {
    public SolidTransportSettings {
        if(!Double.isFinite(immobileViscosity)||immobileViscosity<=0||!Double.isFinite(traceVolumeFraction)||traceVolumeFraction<0||traceVolumeFraction>=1
                ||!Double.isFinite(suspensionMultiplier)||suspensionMultiplier<=0||!Double.isFinite(filterCapacity)||filterCapacity<=0
                ||!Double.isFinite(filterResistance)||filterResistance<=0)throw new IllegalArgumentException("Invalid solid transport settings");
    }
    public static SolidTransportSettings defaults(){return new SolidTransportSettings(100,1e-8,10,.01,1e6);}
}
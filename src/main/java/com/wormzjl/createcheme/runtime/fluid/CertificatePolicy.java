package com.wormzjl.createcheme.runtime.fluid;

/**
 * The rest and steady-flow certificate settings of plan section 4, captured once at server start like the
 * other fluid options. Every value is configurable; the defaults are the ones the owner accepted.
 *
 * @param enabled             {@code restDetection}; off disables certificates entirely
 * @param stationaryTolerance {@code eps_s}: the interval-to-interval change of any conserved quantity, relative
 *                            to its inventory, and of any flow, relative to the island's largest flow, that still
 *                            counts as stationary; 0 admits only intervals that repeat exactly
 * @param inventoryBudget     {@code delta_budget}: the largest relative inventory change replay may extrapolate
 *                            within one certificate window
 * @param maximumIntervals    {@code K_max}: the most intervals one window may replay
 * @param confirmIntervals    consecutive qualifying intervals before an island certifies, and again after a hold
 * @param recheckSeconds      periodic re-solve of an exact-zero rest; 0 means never
 */
public record CertificatePolicy(boolean enabled,double stationaryTolerance,double inventoryBudget,int maximumIntervals,
                                int confirmIntervals,int recheckSeconds) {
    public CertificatePolicy {
        if(!Double.isFinite(stationaryTolerance)||stationaryTolerance<0||stationaryTolerance>1e-6
                ||!Double.isFinite(inventoryBudget)||inventoryBudget<1e-12||inventoryBudget>1e-3
                ||maximumIntervals<1||maximumIntervals>1_000_000||confirmIntervals<1||confirmIntervals>10
                ||recheckSeconds<0||recheckSeconds>86_400)throw new IllegalArgumentException("Invalid certificate policy");
    }
    public static CertificatePolicy defaults(){return new CertificatePolicy(true,1e-7,1e-6,17_280,2,0);}
    /** The same values with certificates off: the scheduling of an island that solves every interval. */
    public static CertificatePolicy disabled(){return defaults().withEnabled(false);}
    public CertificatePolicy withEnabled(boolean value){return new CertificatePolicy(value,stationaryTolerance,inventoryBudget,maximumIntervals,confirmIntervals,recheckSeconds);}
}

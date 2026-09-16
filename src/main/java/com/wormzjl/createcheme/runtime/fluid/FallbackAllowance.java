package com.wormzjl.createcheme.runtime.fluid;

/** Persistent allowance for one approximate episode. Held results and restart do not reset it. */
public record FallbackAllowance(int acceptedIntervals,long advancedTicks,int capturedCadenceTicks) {
    public static final FallbackAllowance NONE=new FallbackAllowance(0,0,0);
    public FallbackAllowance {
        if(acceptedIntervals<0||acceptedIntervals>3||advancedTicks<0
                ||acceptedIntervals==0&&(advancedTicks!=0||capturedCadenceTicks!=0)
                ||acceptedIntervals>0&&(capturedCadenceTicks<20||capturedCadenceTicks>400||advancedTicks==0||advancedTicks>3L*capturedCadenceTicks)) {
            throw new IllegalArgumentException("Invalid fallback allowance snapshot");
        }
    }
    public boolean permits(long durationTicks,int currentCadenceTicks) {
        if(durationTicks<=0||currentCadenceTicks<20||currentCadenceTicks>400)throw new IllegalArgumentException("Invalid approximate duration/cadence");
        int captured=acceptedIntervals==0?currentCadenceTicks:capturedCadenceTicks;
        return acceptedIntervals<3&&durationTicks<=3L*captured-advancedTicks;
    }
    public FallbackAllowance accept(long durationTicks,int currentCadenceTicks) {
        if(!permits(durationTicks,currentCadenceTicks))throw new IllegalStateException("Approximate allowance exhausted");
        return new FallbackAllowance(acceptedIntervals+1,advancedTicks+durationTicks,acceptedIntervals==0?currentCadenceTicks:capturedCadenceTicks);
    }
    /** Call only after a valid full result commits from the current inventory. */
    public FallbackAllowance fullRecovery(){return NONE;}
}

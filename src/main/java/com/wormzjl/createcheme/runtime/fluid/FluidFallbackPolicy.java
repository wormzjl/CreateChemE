package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.ApproximationAnchor;
import java.util.*;

/** Immutable admission-time policy. Proposed allowance changes take effect only with an island commit. */
public record FluidFallbackPolicy(boolean enabled,Optional<ApproximationAnchor> anchor,FallbackAllowance allowance,
                                  int cadenceTicks,long softBudgetNanos) {
    public FluidFallbackPolicy {
        Objects.requireNonNull(anchor);Objects.requireNonNull(allowance);
        if(cadenceTicks<20||cadenceTicks>400||softBudgetNanos<0||enabled&&(anchor.isEmpty()||softBudgetNanos==0)
                ||allowance.acceptedIntervals()>0&&anchor.isEmpty())throw new IllegalArgumentException("Invalid fallback policy");
    }
    public static FluidFallbackPolicy disabled(){return new FluidFallbackPolicy(false,Optional.empty(),FallbackAllowance.NONE,100,0);}
    public static FluidFallbackPolicy active(ApproximationAnchor anchor,FallbackAllowance allowance,int cadenceTicks,long softBudgetNanos) {
        return new FluidFallbackPolicy(true,Optional.of(anchor),allowance,cadenceTicks,softBudgetNanos);
    }
}

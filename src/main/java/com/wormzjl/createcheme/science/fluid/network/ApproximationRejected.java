package com.wormzjl.createcheme.science.fluid.network;

/** Refusal of the approximate mode, without changing any committed inventory or simulation clock. */
public final class ApproximationRejected extends RuntimeException {
    public ApproximationRejected(String message){super(message);}
}

package com.wormzjl.createcheme.runtime.fluid;

import java.util.List;

/** Replaceable equipment policy. Working-capacity kg are separate from reservoir geometric volume. */
@FunctionalInterface
public interface BufferBackpressure {
    boolean mayRun(boolean wasRunning,List<Double> availableFeedKg,List<Double> freeProductKg);

    static BufferBackpressure stopAndResume(double feedResumeKg,double productResumeKg) {
        if(!Double.isFinite(feedResumeKg)||feedResumeKg<=0||!Double.isFinite(productResumeKg)||productResumeKg<=0)throw new IllegalArgumentException("Invalid resume thresholds");
        return (running,feed,product)-> {
            if(feed.isEmpty()||product.isEmpty())return false;
            return feed.stream().allMatch(kg->Double.isFinite(kg)&&(running?kg>0:kg>=feedResumeKg))
                    &&product.stream().allMatch(kg->Double.isFinite(kg)&&(running?kg>0:kg>=productResumeKg));
        };
    }
    static BufferBackpressure defaults(){return stopAndResume(20,20);}
}

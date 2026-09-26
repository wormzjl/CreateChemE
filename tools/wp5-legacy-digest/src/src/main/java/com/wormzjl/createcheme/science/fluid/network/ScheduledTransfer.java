package com.wormzjl.createcheme.science.fluid.network;

/** Request-scoped module boundary rates, integrated in the same reservoir balances as pipe transport. */
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;

public sealed interface ScheduledTransfer permits ScheduledTransfer.Withdrawal,ScheduledTransfer.Injection {
    long id();
    int node();
    /** Bulk withdrawal uses the current coupled candidate's composition and stream enthalpy. */
    record Withdrawal(long id,int node,double massKgPerSecond) implements ScheduledTransfer {
        public Withdrawal {if(node<0||!Double.isFinite(massKgPerSecond)||massKgPerSecond<0)throw new IllegalArgumentException("Invalid scheduled withdrawal");}
    }
    /** The energy rate includes stream energy and gravitational energy in the world's fixed datum. */
    record Injection(long id,int node,double[] molesPerSecond,double totalEnergyPerSecond,SolidInventory solidsPerSecond) implements ScheduledTransfer {
        public Injection(long id,int node,double[] molesPerSecond,double totalEnergyPerSecond){this(id,node,molesPerSecond,totalEnergyPerSecond,SolidInventory.EMPTY);}
        public Injection {
            java.util.Objects.requireNonNull(solidsPerSecond);
            molesPerSecond=molesPerSecond.clone();
            if(node<0||molesPerSecond.length==0||!Double.isFinite(totalEnergyPerSecond))throw new IllegalArgumentException("Invalid scheduled injection");
            double sum=0;for(double n:molesPerSecond){if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException("Invalid injection amount");sum+=n;}
            if(!Double.isFinite(sum)||sum==0&&solidsPerSecond.empty()&&totalEnergyPerSecond!=0)throw new IllegalArgumentException("Invalid empty injection");
        }
        @Override public double[] molesPerSecond(){return molesPerSecond.clone();}
    }
}

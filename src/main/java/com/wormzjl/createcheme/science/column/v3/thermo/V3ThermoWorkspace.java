package com.wormzjl.createcheme.science.column.v3.thermo;

import java.util.Arrays;
import java.util.Objects;

/** Caller-owned mutable scratch storage for exactly one V3 thermodynamic model and solve. */
public final class V3ThermoWorkspace {
    private final V3NextgenPrSession owner;
    final V3NextgenPrSession.Session prSession;
    final double[] normalizedOverall;
    final double[] wilsonK;
    final double[] logK;
    final double[] nextLogK;
    final double[] liquidComposition;
    final double[] vaporComposition;

    V3ThermoWorkspace(V3NextgenPrSession owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.prSession = owner.newSession();
        int componentCount = owner.componentCount();
        this.normalizedOverall = new double[componentCount];
        this.wilsonK = new double[componentCount];
        this.logK = new double[componentCount];
        this.nextLogK = new double[componentCount];
        this.liquidComposition = new double[componentCount];
        this.vaporComposition = new double[componentCount];
    }

    public int componentCount() {
        return normalizedOverall.length;
    }

    /** Clears retained numerical values without releasing this workspace to another solve/model. */
    public void clear() {
        Arrays.fill(normalizedOverall, 0.0);
        Arrays.fill(wilsonK, 0.0);
        Arrays.fill(logK, 0.0);
        Arrays.fill(nextLogK, 0.0);
        Arrays.fill(liquidComposition, 0.0);
        Arrays.fill(vaporComposition, 0.0);
        prSession.clear();
    }

    void requireOwner(V3NextgenPrSession candidate) {
        if (owner != candidate) throw new IllegalArgumentException("V3 thermodynamic workspace belongs to another model");
    }
}

package com.wormzjl.createcheme.science.material;

import java.util.Objects;
import java.util.function.Supplier;

/** Atomically published catalog and explicitly bounded calculation context for legacy static evaluators. */
public final class MaterialRuntime {
    private static volatile MaterialCatalog active;
    private record Context(MaterialCatalog catalog, String packageId) {}
    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();
    private MaterialRuntime() {}
    public static MaterialCatalog active() { MaterialCatalog c=active; return c==null?MaterialCatalog.bundled():c; }
    public static MaterialCatalog current() { Context c=CONTEXT.get(); return c==null?active():c.catalog(); }
    /** Resolves transport data from the same immutable snapshot as the calling calculation. */
    public static java.util.Optional<ViscosityCorrelation> viscosity(String packageId, String componentId, ViscosityCorrelation.Phase phase) {
        return current().viscosity(packageId, componentId, phase);
    }
    /** Only the server reload adapter publishes; parse/validation must already have succeeded. */
    public static void publish(MaterialCatalog catalog) { active=Objects.requireNonNull(catalog); }
    public static void reset() { active=null; }
    public static MaterialCatalog.Water water() {
        Context c=CONTEXT.get();
        String id=c==null || !c.catalog().packages().containsKey(c.packageId())?"createcheme:tjl20_methane":c.packageId();
        return current().requirePackage(id).water();
    }
    /** Restores an enclosing context on every exit, including cancellation and failure. */
    public static <T> T with(MaterialCatalog catalog,String packageId,Supplier<T> action) {
        Context previous=CONTEXT.get();
        CONTEXT.set(new Context(Objects.requireNonNull(catalog),Objects.requireNonNull(packageId)));
        try{return action.get();}finally{if(previous==null)CONTEXT.remove();else CONTEXT.set(previous);}
    }
    public static <T> T pinned(String packageId,Supplier<T> action) {
        return with(current(),packageId,action);
    }
    public static boolean isBundledScience(String packageId) {
        var baseline=MaterialCatalog.bundled().packages().get(packageId);
        return baseline!=null && baseline.fingerprint().equals(current().requirePackage(packageId).fingerprint());
    }
    /** Cached and completed results are current only for the exact resolved scientific dataset. */
    public static boolean isCurrent(String packageId, String scientificRevision) {
        var p=current().packages().get(packageId);
        return p!=null && p.scientificRevision().equals(scientificRevision);
    }
    public static MaterialName name(String packageId,String componentId) {
        if(componentId.equals("h2o"))return current().name("Water");
        var p=current().packages().get(packageId);
        return current().name(p==null?componentId:p.canonicalId(componentId));
    }
}

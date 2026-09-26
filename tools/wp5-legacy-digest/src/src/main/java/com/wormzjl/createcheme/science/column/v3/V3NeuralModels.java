package com.wormzjl.createcheme.science.column.v3;

import java.io.IOException;

/** Bundled qualified models; an absent or invalid registry preserves classical fallback. */
public final class V3NeuralModels {
    public static final String REGISTRY="/data/createcheme/neural/registry.json";
    private V3NeuralModels() {}
    public static V3NeuralInitializer bundled(){return Holder.MODEL;}
    private static V3NeuralInitializer load() {
        try(var stream=V3NeuralModels.class.getResourceAsStream(REGISTRY)) {
            return V3NeuralRegistry.read(stream,V3NeuralModels.class::getResourceAsStream);
        } catch(IOException|IllegalArgumentException invalid) {
            System.getLogger(V3NeuralModels.class.getName()).log(System.Logger.Level.WARNING,
                    "Bundled neural registry unavailable; using classical initialization",invalid);
            return V3NeuralInitializer.UNAVAILABLE;
        }
    }
    private static final class Holder {private static final V3NeuralInitializer MODEL=load();}
}

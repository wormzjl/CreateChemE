package com.wormzjl.createcheme.client;

/** Resource-pack translations over server-owned bounded scientific naming descriptors. */
public final class MaterialNames {
    private MaterialNames() {}
    public static String localized(com.wormzjl.createcheme.science.material.MaterialName n) {
        String base=net.minecraft.network.chat.Component.translatableWithFallback(n.translationKey(),n.fallback()).getString();
        if(!n.kind().equals("petroleum_fraction"))return base;
        String range;
        if(n.lowerKelvin()==null)range=net.minecraft.network.chat.Component.translatableWithFallback(n.rangeKey(),"%s, NBP below %s°C",base,n.upperCelsius()).getString();
        else if(n.upperKelvin()==null)range=net.minecraft.network.chat.Component.translatableWithFallback(n.rangeKey(),"%s, NBP above %s°C",base,n.lowerCelsius()).getString();
        else range=net.minecraft.network.chat.Component.translatableWithFallback(n.rangeKey(),"%s, NBP %s–%s°C",base,n.lowerCelsius(),n.upperCelsius()).getString();
        return range+(n.estimated()?net.minecraft.network.chat.Component.translatableWithFallback("material.createcheme.estimated"," (estimated)").getString():"");
    }
}

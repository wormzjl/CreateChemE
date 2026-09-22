package com.wormzjl.createcheme.world.item;

import com.google.gson.Gson;
import com.wormzjl.createcheme.runtime.fluid.RecoveredSolid;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import java.util.*;

/** Non-stackable recovered matter; v1 intentionally has no reinjection behavior. */
public final class RecoveredSolidsItem extends Item {
    private static final Gson JSON=new Gson();
    private static final String KEY="createcheme_solid_parcel";
    public static final String REFERENCE="createcheme:solid-sensible-298.15K-v1";
    public record Contents(UUID transfer,SolidInventory solids,double energyJoule,String reference) {
        public Contents {
            Objects.requireNonNull(transfer);Objects.requireNonNull(solids);
            if(solids.empty()||!Double.isFinite(energyJoule)||!REFERENCE.equals(reference))throw new IllegalArgumentException("Invalid recovered solids");
        }
    }
    public RecoveredSolidsItem(Properties properties){super(properties.stacksTo(1));}
    public static ItemStack create(UUID transfer,RecoveredSolid recovered) {
        var stack=new ItemStack(com.wormzjl.createcheme.registry.ModItems.RECOVERED_SOLIDS.get());
        var tag=new CompoundTag();tag.putString(KEY,JSON.toJson(new Contents(transfer,recovered.solids(),recovered.energyJoule(),REFERENCE)));
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return stack;
    }
    public static boolean carriesTransfer(ItemStack stack,UUID transfer) {
        return stack.is(com.wormzjl.createcheme.registry.ModItems.RECOVERED_SOLIDS.get())&&contents(stack).map(c->c.transfer().equals(transfer)).orElse(false);
    }
    public static Optional<Contents> contents(ItemStack stack) {
        var data=stack.get(DataComponents.CUSTOM_DATA);if(data==null)return Optional.empty();
        String value=data.copyTag().getString(KEY);if(value.isEmpty()||value.length()>262144)return Optional.empty();
        try{return Optional.ofNullable(JSON.fromJson(value,Contents.class));}catch(RuntimeException invalid){return Optional.empty();}
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> tooltip,TooltipFlag flag) {
        var value=contents(stack);
        if(value.isEmpty()){tooltip.add(Component.literal("No captured solids"));return;}
        var contents=value.orElseThrow();
        tooltip.add(Component.literal(String.format(Locale.ROOT,"Captured solids: %.6g kg",contents.solids().massKg())));
        int shown=0;for(var p:contents.solids().populations()){
            if(shown++==8){tooltip.add(Component.literal("More particle grades stored in this container"));break;}
            tooltip.add(Component.literal(String.format(Locale.ROOT,"%s · %.6g µm · %.6g kg",p.material().id(),p.size().diameterMetres()*1e6,p.massKg())));
        }
    }
}
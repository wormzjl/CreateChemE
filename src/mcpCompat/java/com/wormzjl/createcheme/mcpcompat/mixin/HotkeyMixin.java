package com.wormzjl.createcheme.mcpcompat.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;

/** Synthetic GLFW callbacks do not update GLFW's polled modifier state. Preserve Ctrl+A semantics. */
@Pseudo
@Mixin(targets="xyz.langyo.minecraft.mcp.common.ReflectedInputHandler",remap=false)
public abstract class HotkeyMixin {
    @Inject(method="hotkey",at=@At("HEAD"),cancellable=true,remap=false)
    private void selectAll(String[] keys,CallbackInfo callback) {
        boolean control=Arrays.stream(keys).map(k->k.toLowerCase(Locale.ROOT)).anyMatch(k->k.contains("control")||k.equals("ctrl"));
        boolean a=Arrays.stream(keys).anyMatch(k->k.equalsIgnoreCase("a")||k.equalsIgnoreCase("key.keyboard.a"));
        if(!control||!a)return;
        var client=Minecraft.getInstance();client.execute(()->{
            if(client.screen==null)return;GuiEventListener focused=client.screen.getFocused();
            while(focused instanceof ContainerEventHandler container&&container.getFocused()!=null)focused=container.getFocused();
            if(focused instanceof EditBox edit){edit.setCursorPosition(edit.getValue().length());edit.setHighlightPos(0);}
        });callback.cancel();
    }
}

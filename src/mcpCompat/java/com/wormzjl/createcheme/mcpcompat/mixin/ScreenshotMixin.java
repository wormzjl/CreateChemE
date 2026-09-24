package com.wormzjl.createcheme.mcpcompat.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Captures the real framebuffer instead of MCP 0.3.0's cached 854 x 480 crop. */
@Pseudo
@Mixin(targets="xyz.langyo.minecraft.mcp.common.ScreenshotHelper",remap=false)
public abstract class ScreenshotMixin {
    @Inject(method="takeScreenshot",at=@At("HEAD"),cancellable=true,remap=false)
    private static void capture(Object instance,int width,int height,CallbackInfoReturnable<byte[]> result){
        if(!(instance instanceof Minecraft client))return;
        try(var image=Screenshot.takeScreenshot(client.getMainRenderTarget())){
            result.setReturnValue(image.asByteArray());
        }catch(IOException e){throw new UncheckedIOException("Cannot encode MCP framebuffer",e);}
    }
}

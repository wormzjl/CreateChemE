package com.wormzjl.createcheme.mcpcompat.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MCP 0.3.0 blocks the render thread during drags, preventing Minecraft's per-frame drag dispatch. */
@Pseudo
@Mixin(targets="xyz.langyo.minecraft.mcp.common.InputInjectionHelper",remap=false)
public abstract class DragInputMixin {
    @Inject(method="sendMouseDrag",at=@At("HEAD"),cancellable=true,remap=false)
    private static void drag(long handle,int startX,int startY,int endX,int endY,int button,int steps,CallbackInfo result){
        var client=Minecraft.getInstance();var screen=client.screen;
        if(screen==null)return;
        var window=client.getWindow();
        double scaleX=(double)window.getGuiScaledWidth()/window.getWidth();
        double scaleY=(double)window.getGuiScaledHeight()/window.getHeight();
        double x=startX*scaleX,y=startY*scaleY;
        screen.mouseClicked(x,y,button);
        int count=Math.clamp(steps,1,256);
        for(int i=1;i<=count;i++){
            double nextX=(startX+(endX-startX)*(double)i/count)*scaleX;
            double nextY=(startY+(endY-startY)*(double)i/count)*scaleY;
            screen.mouseMoved(nextX,nextY);screen.mouseDragged(nextX,nextY,button,nextX-x,nextY-y);
            x=nextX;y=nextY;
        }
        screen.mouseReleased(x,y,button);result.cancel();
    }
}

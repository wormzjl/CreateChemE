package com.wormzjl.createcheme.mcpcompat.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** MCP 0.3.0 misses inherited interface charTyped and falls back to Screen.insertText, a no-op.
 * Forward the already-authorized MCP input to Minecraft's real focused widget and responder.
 */
@Pseudo
@Mixin(targets="xyz.langyo.minecraft.mcp.common.ScreenInteractionHelper",remap=false)
public abstract class ScreenInputMixin {
    @Inject(method="guiClick",at=@At("HEAD"),cancellable=true,remap=false)
    private static void click(Object instance,int x,int y,int button,CallbackInfoReturnable<String> result) {
        if(instance instanceof Minecraft client&&client.screen==null&&client.player!=null&&button==0) {
            result.setReturnValue("{\"clicked\":"+((MinecraftInputInvoker)client).createchemeMcpAttack()+",\"method\":\"minecraft_attack_path\"}");return;
        }
        if(instance instanceof Minecraft client&&client.screen!=null) {
            var window=client.getWindow();
            double guiX=(double)x*window.getGuiScaledWidth()/window.getWidth();
            double guiY=(double)y*window.getGuiScaledHeight()/window.getHeight();
            boolean accepted=client.screen.mouseClicked(guiX,guiY,button);
            client.screen.mouseReleased(guiX,guiY,button);
            result.setReturnValue("{\"clicked\":"+accepted+",\"method\":\"minecraft_interface_dispatch\"}");
        }
    }
    @Inject(method="guiCharType",at=@At("HEAD"),cancellable=true,remap=false)
    private static void character(Object instance,char character,int modifiers,CallbackInfoReturnable<String> result) {
        if(instance instanceof Minecraft client&&client.screen!=null) {
            boolean accepted=client.screen.charTyped(character,modifiers);
            result.setReturnValue("{\"charTyped\":"+accepted+",\"method\":\"minecraft_interface_dispatch\"}");
        }
    }
    @Inject(method="pasteText",at=@At("HEAD"),cancellable=true,remap=false)
    private static void paste(Object instance,String text,CallbackInfoReturnable<String> result) {
        if(instance instanceof Minecraft client&&client.screen!=null) {
            GuiEventListener focused=client.screen.getFocused();
            while(focused instanceof ContainerEventHandler container&&container.getFocused()!=null)focused=container.getFocused();
            if(focused instanceof EditBox edit) {
                edit.insertText(text);result.setReturnValue("{\"pasted\":true,\"method\":\"focused_edit_box\"}");
            }
        }
    }
}

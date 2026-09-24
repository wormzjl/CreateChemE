package com.wormzjl.createcheme.client.gui.common;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import static org.junit.jupiter.api.Assertions.*;

class TextInputPriorityTest {
    private static final class Form extends Screen {Form(){super(Component.literal("Test form"));}}
    @Test void focusedNativeEditBoxConsumesInventoryAndOtherPrintableKeyBindings(){
        var screen=new Form();var edit=new EditBox(new Font(id->null,false),0,0,150,18,Component.literal("Input"));
        screen.setFocused(edit);edit.setFocused(true);
        assertTrue(ProcessUi.textKeyPressed(screen,GLFW.GLFW_KEY_E,0,0));
        assertTrue(ProcessUi.textKeyPressed(screen,GLFW.GLFW_KEY_1,0,0));
        assertTrue(ProcessUi.textKeyPressed(screen,GLFW.GLFW_KEY_ENTER,0,0));
        assertFalse(ProcessUi.textKeyPressed(screen,GLFW.GLFW_KEY_ESCAPE,0,0));
        assertFalse(ProcessUi.textKeyPressed(screen,GLFW.GLFW_KEY_TAB,0,0));
        edit.setFocused(false);assertFalse(ProcessUi.textKeyPressed(screen,GLFW.GLFW_KEY_E,0,0));
    }
    @Test void noFocusedEditorLeavesTheNormalScreenBindingsAlone(){
        assertFalse(ProcessUi.textKeyPressed(new Form(),GLFW.GLFW_KEY_E,0,0));
    }
}

package com.wormzjl.createcheme.runtime.fluid;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client half of the opt-in in-game measurement log (WP5 rig): once a second, the frame rate the client reports
 * ({@link Minecraft#getFps()}) goes to {@code fluid_diag_fps.csv} in the game directory. Registered only on a physical
 * client and only when {@link FluidInGameDiagnostics#enabled()}; otherwise this class is never loaded.
 */
public final class FluidInGameClientDiagnostics {
    private static final Logger LOGGER=LogUtils.getLogger();
    private static BufferedWriter out;
    private static long last;

    private FluidInGameClientDiagnostics() {}

    public static void register(IEventBus bus) {
        try {
            out=Files.newBufferedWriter(Path.of("fluid_diag_fps.csv"));out.write("epoch_ms,fps\n");out.flush();
        } catch(IOException exception){LOGGER.warn("fluid_diag cannot write fluid_diag_fps.csv",exception);return;}
        bus.addListener(FluidInGameClientDiagnostics::tick);
    }

    private static void tick(ClientTickEvent.Post event) {
        long now=System.currentTimeMillis();if(out==null||now-last<1000)return;last=now;
        try{out.write(now+","+Minecraft.getInstance().getFps()+"\n");out.flush();}catch(IOException exception){out=null;}
    }
}

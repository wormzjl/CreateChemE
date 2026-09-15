package com.wormzjl.createcheme.material;

import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialRuntime;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import java.io.*;
import java.util.*;

/** One transaction for all material resource kinds; ResourceManager supplies whole-record pack precedence. */
public final class MaterialReloadListener extends SimplePreparableReloadListener<MaterialCatalog> {
    public static void register(AddReloadListenerEvent event) { event.addListener(new MaterialReloadListener()); }
    @Override protected MaterialCatalog prepare(ResourceManager manager,ProfilerFiller profiler) {
        Map<String,String> resources=new TreeMap<>();
        manager.listResources("materials",id -> id.getPath().endsWith(".json")).forEach((id,resource)-> {
            try(Reader reader=resource.openAsReader()) {
                StringBuilder text=new StringBuilder(); char[] buffer=new char[8192]; int count;
                while((count=reader.read(buffer))!=-1)text.append(buffer,0,count);
                resources.put("data/"+id.getNamespace()+"/"+id.getPath(),text.toString());
            }catch(IOException e){throw new UncheckedIOException(id.toString(),e);}
        });
        return MaterialCatalog.parse(resources);
    }
    @Override protected void apply(MaterialCatalog catalog,ResourceManager manager,ProfilerFiller profiler) {
        MaterialRuntime.publish(catalog);
    }
}

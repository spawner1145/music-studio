package dev.musicstudio.client;

import com.google.gson.reflect.TypeToken;
import dev.musicstudio.MusicStudio;
import dev.musicstudio.song.ProjectStore;
import dev.musicstudio.song.StudioException;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.io.Reader;
import java.util.*;

/** The same en_us/zh_cn language resources serve automatic and explicit editor language modes. */
public final class StudioText {
    private static Map<String,String> english=Map.of(),chinese=Map.of();
    private static boolean dirty=true,preferencesLoaded;
    private static String mode="auto";
    private static float zoom=1.15f;
    private StudioText(){}
    public static void invalidate(){dirty=true;}
    public static String locale(){
        loadPreferences();if(!mode.equals("auto"))return mode;
        return Minecraft.getInstance().options.languageCode.startsWith("zh")?"zh_cn":"en_us";
    }
    public static String tr(String key,Object... arguments){
        if(dirty)reload();String full="musicstudio."+key;
        String template=(locale().equals("zh_cn")?chinese:english).getOrDefault(full,english.getOrDefault(full,full));
        if(arguments.length==0)return template;
        try{return String.format(Locale.ROOT,template,arguments);}catch(IllegalFormatException ex){return template;}
    }
    public static String error(Throwable ex){
        if(ex instanceof StudioException e)return tr(e.key,e.arguments);
        if(ex instanceof NumberFormatException)return tr("error.number");
        return ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage();
    }
    public static String named(String key,String fallback){
        if(key==null||key.isBlank())return fallback;
        String text=tr(key);return text.startsWith("musicstudio.")?fallback:text;
    }
    public static float zoom(){loadPreferences();return zoom;}
    public static void adjustZoom(int direction){loadPreferences();zoom=Math.max(1f,Math.min(1.5f,Math.round((zoom+direction*.05f)*100)/100f));savePreferences();}
    public static String mode(){loadPreferences();return mode;}
    public static void cycleLanguage(){loadPreferences();mode=switch(mode){case "auto"->"zh_cn";case "zh_cn"->"en_us";default->"auto";};savePreferences();}
    private static Path preferences(){return Minecraft.getInstance().gameDirectory.toPath().resolve("musicstudio/client.json");}
    private static void loadPreferences(){
        if(preferencesLoaded)return;preferencesLoaded=true;
        Path path=preferences();if(!Files.isRegularFile(path))return;
        try{if(Files.size(path)>4096)return;var data=ProjectStore.GSON.fromJson(Files.readString(path),com.google.gson.JsonObject.class);
            if(data.has("language")&&Set.of("auto","en_us","zh_cn").contains(data.get("language").getAsString()))mode=data.get("language").getAsString();
            if(data.has("fontZoom")){float value=data.get("fontZoom").getAsFloat();if(Float.isFinite(value))zoom=Math.max(1,Math.min(1.5f,value));}
        }catch(Exception ex){MusicStudio.LOGGER.warn("Could not load Studio preferences",ex);}
    }
    private static void savePreferences(){
        try{Path path=preferences();Files.createDirectories(path.getParent());Path tmp=Files.createTempFile(path.getParent(),"client-",".tmp");try{Files.writeString(tmp,ProjectStore.GSON.toJson(Map.of("language",mode,"fontZoom",zoom)));Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}}
        catch(Exception ex){MusicStudio.LOGGER.warn("Could not save Studio preferences",ex);}
    }
    private static void reload(){english=read("en_us");chinese=read("zh_cn");dirty=false;}
    private static Map<String,String> read(String language){
        try{var resources=Minecraft.getInstance().getResourceManager().getResourceStack(ResourceLocation.fromNamespaceAndPath(MusicStudio.ID,"lang/"+language+".json"));
            Map<String,String> combined=new HashMap<>();
            for(var resource:resources)try(Reader reader=resource.openAsReader()){Map<String,String> values=ProjectStore.GSON.fromJson(reader,new TypeToken<Map<String,String>>(){}.getType());if(values!=null)combined.putAll(values);}
            return Map.copyOf(combined);
        }catch(Exception ex){MusicStudio.LOGGER.error("Could not load Studio language {}",language,ex);return Map.of();}
    }
    // Development smoke tests do not change the player's persisted preferences.
    static void testLanguage(String language){loadPreferences();mode=language;}
}

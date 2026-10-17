package dev.musicstudio.client;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.nio.file.Path;

final class MediaDialogs {
    private MediaDialogs(){}
    static Path project(boolean save,Path current,String title){try(var stack=MemoryStack.stackPush()){
        String base=current==null?MinecraftPaths.defaultProject(title):current.toAbsolutePath().toString();var filters=stack.mallocPointer(save?1:2).put(stack.UTF8("*.musicstudio"));if(!save)filters.put(stack.UTF8("*.json"));filters.flip();
        String path=save?TinyFileDialogs.tinyfd_saveFileDialog(StudioText.tr("files.save_project_folder"),base,filters,"Music Studio"):TinyFileDialogs.tinyfd_openFileDialog(StudioText.tr("action.open"),base,filters,"Music Studio / JSON",false);
        if(path==null)return null;if(save&&!path.toLowerCase(java.util.Locale.ROOT).endsWith(".musicstudio"))path+=".musicstudio";return Path.of(path);
    }}
    private static final class MinecraftPaths {static String defaultProject(String title){return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("musicstudio/projects").resolve(dev.musicstudio.media.ProjectFiles.defaultName(title)+".musicstudio").toAbsolutePath().toString();}}
    static Path open(boolean midi){try(var stack=MemoryStack.stackPush()){String[] extensions=midi?new String[]{"*.mid","*.midi"}:new String[]{"*.wav","*.ogg","*.mp3","*.flac","*.aif","*.aiff","*.m4a"};var filters=stack.mallocPointer(extensions.length);for(String extension:extensions)filters.put(stack.UTF8(extension));filters.flip();String path=TinyFileDialogs.tinyfd_openFileDialog(StudioText.tr(midi?"media.import_midi":"media.import_sample"),"",filters,midi?"MIDI":"Audio",false);return path==null?null:Path.of(path);}}
    static Path folder(){String path=TinyFileDialogs.tinyfd_selectFolderDialog(StudioText.tr("media.export_midi"),"");return path==null?null:Path.of(path);}
    static Path save(String name,String extension){try(var stack=MemoryStack.stackPush()){var filter=stack.mallocPointer(1).put(stack.UTF8("*."+extension)).flip();String path=TinyFileDialogs.tinyfd_saveFileDialog(StudioText.tr("media.export_audio"),name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_. -]","_")+"."+extension,filter,extension.toUpperCase());if(path==null)return null;return Path.of(path.toLowerCase(java.util.Locale.ROOT).endsWith("."+extension)?path:path+"."+extension);}}
}

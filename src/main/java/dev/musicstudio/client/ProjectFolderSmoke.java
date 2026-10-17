package dev.musicstudio.client;

import dev.musicstudio.MusicStudio;
import dev.musicstudio.media.*;
import dev.musicstudio.song.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import java.nio.file.*;

/** Folder save/open and playback regression in the isolated development client. */
public final class ProjectFolderSmoke {
    private static boolean started;private static int warmup,stage,age;private static volatile int sampleSounds;
    private static Path root,renamed,original;private static EditorScreen editor;
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException("PROJECT_FOLDER_SMOKE: "+message);}
    private static void next(){stage++;age=0;MusicStudio.LOGGER.info("PROJECT_FOLDER_SMOKE stage {}",stage);}
    public static void tick(){var mc=Minecraft.getInstance();if(mc.getOverlay()!=null)return;
        if(!started&&mc.screen instanceof TitleScreen&&++warmup>40){started=true;root=mc.gameDirectory.toPath().toAbsolutePath().normalize().resolve("musicstudio/project-folder-smoke-"+System.currentTimeMillis());
            NeoForge.EVENT_BUS.addListener((PlaySoundSourceEvent e)->{if(e.getSound().getLocation().toString().startsWith("musicstudio:sample/"))sampleSounds++;});
            NeoForge.EVENT_BUS.addListener((PlayStreamingSourceEvent e)->{if(e.getSound().getLocation().toString().startsWith("musicstudio:sample/"))sampleSounds++;});
            try{Files.createDirectories(root);float[] pcm=new float[52920];for(int i=0;i<pcm.length;i++)pcm[i]=(float)(.4*Math.sin(i*2*Math.PI*440/44100));AudioFiles.wav(root.resolve("source.wav"),new AudioFiles.Wave(pcm,pcm.clone()),1);}catch(Exception e){throw new IllegalStateException(e);}
            StudioText.testLanguage("zh_cn");editor=EditorScreen.create();mc.setScreen(editor);editor.canvas.smokeMediaHome(ArrangementSmoke.fixture());editor.canvas.importSample(root.resolve("source.wav"));
        }
        if(!started)return;if(++age>1600)throw new IllegalStateException("Project folder smoke timeout at "+stage);if(age<10||!editor.canvas.smokeMediaIdle())return;
        try{switch(stage){
            case 0->{check(editor.canvas.smokeMediaSong().assets.size()==1,"Import failed");editor.canvas.smokeProjectSave(root.resolve("分享项目.musicstudio"),true);next();}
            case 1->{original=root.resolve("分享项目/分享项目.musicstudio");check(original.equals(editor.canvas.smokeProjectFile()),"Save As did not select the folder's file");check(!Files.exists(root.resolve("分享项目.musicstudio")),"Flat score left outside the project");check(editor.canvas.smokeMediaSong().projectDirectory.equals(original.getParent()),"Media root did not rebind");editor.canvas.smokeProjectHistory();editor.canvas.smokeProjectSave(original,false);next();}
            case 2->{check(Files.isRegularFile(original.resolveSibling("分享项目.musicstudio.bak")),"Normal save skipped backup");Path newFolder=root.resolve("外层改名");check(original.getParent().startsWith(root)&&newFolder.startsWith(root),"Rename outside smoke directory");Files.move(original.getParent(),newFolder);renamed=newFolder.resolve("内部改名.musicstudio");Files.move(newFolder.resolve(original.getFileName()),renamed);editor.canvas.smokeProjectOpen(renamed);next();}
            case 3->{check(renamed.equals(editor.canvas.smokeProjectFile()),"Renamed file not active");var song=editor.canvas.smokeMediaSong();check(renamed.getParent().equals(song.projectDirectory),"Renamed folder not active");for(var asset:song.assets)check(Files.isRegularFile(ProjectMedia.wav(ClientMedia.projects(),song,asset.id)),"Renamed project missing audio");editor.canvas.smokeOpenImportedAudio();next();}
            case 4->{editor.canvas.smokeSampleEdits();check(renamed.getParent().equals(editor.canvas.smokeMediaSong().projectDirectory),"Sample edit/undo lost media directory");editor.canvas.smokeSamplePreview();next();}
            case 5->{if(sampleSounds==0){if(age>250)throw new IllegalStateException("Copied project sample never reached an audio source");break;}editor.canvas.exportAudio(root.resolve("renamed-mix.wav"),"wav",false);next();}
            case 6->{check(Files.size(root.resolve("renamed-mix.wav"))>44,"Copied project could not render audio");editor.canvas.smokeProjectSave(renamed,false);next();}
            case 7->{check(!Files.exists(renamed.getParent().resolve("内部改名")),"Normal Save created a nested folder after rename");editor.canvas.smokeProjectSave(root.resolve("副本.musicstudio"),true);next();}
            case 8->{Path copy=root.resolve("副本/副本.musicstudio");check(copy.equals(editor.canvas.smokeProjectFile()),"Save As copy did not rebind");var a=ProjectFiles.open(renamed,root.resolve("unused-cache"));var b=ProjectFiles.open(copy,root.resolve("unused-cache"));check(ProjectStore.encode(a).equals(ProjectStore.encode(b)),"Save As changed the score");check(!a.projectDirectory.equals(b.projectDirectory),"Project copies share an absolute media binding");check(!Files.exists(root.resolve("unused-cache")),"Opening folders extracted media into a separate cache");editor.canvas.filesDropped(java.util.List.of(renamed.getParent()),0,0);next();}
            case 9->{check(renamed.equals(editor.canvas.smokeProjectFile()),"Open by folder guessed the old filename");editor.canvas.smokeProjectHistory();editor.canvas.smokeProjectSave(renamed,false);next();}
            case 10->{check(ProjectFiles.open(renamed,root.resolve("unused-cache")).bpm==editor.canvas.smokeMediaSong().bpm,"Save after folder open wrote elsewhere");Files.writeString(root.resolve("complete.txt"),"PASS: native editor save/Save As/open; adjacent assets; independent directory/file rename; undo/redo; real sample source; WAV rendering; two project copies; no external media extraction.");MusicStudio.LOGGER.info("PROJECT_FOLDER_SMOKE PASS: folder distribution, independent renames, real sample playback, WAV, save-as, normal save and undo; artifacts {}",root);mc.stop();}
            default->{}
        }}catch(Exception e){throw new IllegalStateException("Project folder smoke stage "+stage,e);}
    }
}

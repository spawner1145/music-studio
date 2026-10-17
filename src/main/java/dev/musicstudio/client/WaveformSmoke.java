package dev.musicstudio.client;

import dev.musicstudio.MusicStudio;
import dev.musicstudio.media.*;
import dev.musicstudio.song.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import java.nio.file.*;
import java.util.*;

/** Explicit development opt-in. Exercises real resource PCM without opening any player world. */
public final class WaveformSmoke {
    private static int warmup,stage,age;private static EditorScreen editor;private static float peak;private static Path root;private static SongWaveform prior;
    private static void check(boolean condition,String text){if(!condition)throw new IllegalStateException("WAVEFORM_SMOKE: "+text);}
    private static void next(){stage++;age=0;MusicStudio.LOGGER.info("WAVEFORM_SMOKE stage {}",stage);}
    private static float peak(SongWaveform.Columns columns){float p=0;for(float v:columns.high())p=Math.max(p,Math.abs(v));for(float v:columns.low())p=Math.max(p,Math.abs(v));return p;}
    private static void picture(String name){var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),c->{});}
    public static void tick(){var mc=Minecraft.getInstance();if(mc.getOverlay()!=null)return;
        if(editor==null){if(!(mc.screen instanceof TitleScreen)||++warmup<40)return;var s=SongProject.legacyDemo();s.channels=new ArrayList<>(s.channels.subList(2,4));s.channels.get(0).sound="minecraft:block.note_block.harp";s.channels.get(0).volume=.8f;s.channels.get(1).muted=true;s.patterns.clear();var p=new SongProject.Pattern("PCM test",2);p.length=4*SongProject.PPQ;p.notes.get(0).add(new SongProject.Note(0,2,66,127));p.notes.get(0).add(new SongProject.Note(8,2,66,64));p.notes.get(1).add(new SongProject.Note(4,2,66,127));s.patterns.add(p);s.clips.clear();for(int c=0;c<2;c++)s.clips.add(new SongProject.Clip(c,0,0,1));s.endPpq=8*SongProject.PPQ;s.loop=false;root=mc.gameDirectory.toPath().resolve("musicstudio/waveform-smoke-"+System.currentTimeMillis());StudioText.testLanguage("zh_cn");editor=EditorScreen.create();mc.setScreen(editor);editor.canvas.smokeWaveformHome(s);next();return;}
        if(++age>800)throw new IllegalStateException("Waveform smoke timeout at "+stage+": "+editor.canvas.smokeWaveformError());var data=editor.canvas.smokeWaveform();
        switch(stage){
            case 1->{if(data!=null){check(data.seconds()==4,"Song waveform duration differs from arrangement");peak=peak(data.columns(0,.2,129));check(peak>.001,"Real harp audio produced an empty waveform");check(peak(data.columns(.5,.9,129))==0,"Muted track or rest produced signal");picture("musicstudio-waveform-overview.png");editor.canvas.exportAudio(root.resolve("actual-mix.wav"),"wav",false);next();}}
            case 2->{if(age>5&&editor.canvas.smokeMediaIdle()&&data!=null){try{var decoded=AudioFiles.read(root.resolve("actual-mix.wav"));var exported=SongWaveform.from(new AudioFiles.Mix(decoded,1,1),()->false).columns(0,4,129);var shown=data.columns(0,4,129);for(int i=0;i<129;i++){check(Math.abs(exported.low()[i]-shown.low()[i])<.0001,"Waveform differs from exported PCM");check(Math.abs(exported.high()[i]-shown.high()[i])<.0001,"Waveform differs from exported PCM");}}catch(Exception e){throw new IllegalStateException(e);}editor.canvas.smokeWaveformPlay();next();}}
            case 3->{if(age==3)picture("musicstudio-waveform-playing.png");if(age>6){editor.canvas.smokeWaveformStop();editor.canvas.smokeWaveformClick("mute-0");check(editor.canvas.smokeWaveform()==null,"Editing left stale waveform visible");next();}}
            case 4->{if(data!=null){check(peak(data.columns(0,4,129))==0,"All-muted project did not become flat");picture("musicstudio-waveform-muted.png");editor.canvas.smokeWaveformClick("mute-0");editor.canvas.smokeWaveformHalfVolume();next();}}
            case 5->{if(data!=null){check(Math.abs(peak(data.columns(0,.2,129))-peak*.5f)<.0001,"Volume edit did not update actual waveform amplitude");editor.canvas.smokeWaveformClick("mode");next();}}
            case 6->{if(data!=null&&data.seconds()==2){prior=data;mc.reloadResourcePacks();next();}}
            case 7->{if(data!=null&&data!=prior){check(data.seconds()==2,"Reload lost Pattern mode waveform");try{Files.createDirectories(root);Files.writeString(root.resolve("complete.txt"),"Actual Minecraft resource PCM, WAV comparison, mute/rest silence, volume refresh, live/overview display, Pattern mode and resource-reload refresh verified.");}catch(Exception e){throw new IllegalStateException(e);}MusicStudio.LOGGER.info("WAVEFORM_SMOKE PASS: actual resource PCM matches export; live/overview rendering, mute/rest silence, volume edits, Pattern mode and resource reload. Artifacts: {}",root);mc.stop();}}
            default->{}
        }
    }
}

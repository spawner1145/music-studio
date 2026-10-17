package dev.musicstudio.client;

import dev.musicstudio.MusicStudio;
import dev.musicstudio.song.SongProject;
import dev.musicstudio.song.SongProject.*;
import dev.musicstudio.media.AudioFiles;
import net.minecraft.client.*;
import java.nio.file.*;
import java.util.*;

/** Explicit studioSmoke development run, never enabled in an installed mod. */
final class ArrangementSmoke {
    private static int stage,age,ticks;
    private static Path root;
    static int sampleTriggers;
    static SongProject fixture(){
        SongProject s=new SongProject();s.schemaVersion=2;s.loop=false;s.endPpq=8*4*SongProject.PPQ;s.maxX=128;s.maxZ=64;s.maxY=32;
        var harp=new Channel("Harp","minecraft:block.note_block.harp",0x577e87);harp.instrument="harp";var bass=new Channel("Bass","minecraft:block.note_block.bass",0x876d80);bass.instrument="bass";s.channels.addAll(List.of(harp,bass));
        Pattern p=new Pattern("Melody · two sources",2);p.length=4*SongProject.PPQ;p.notes.get(0).add(new Note(0,1,66,127));p.notes.get(1).add(new Note(4,1,69,127));s.patterns.add(p);
        p=new Pattern("Variation",2);p.length=4*SongProject.PPQ;p.color=0x876d80;p.notes.get(0).add(new Note(2,1,72,127));s.patterns.add(p);
        for(int i=0;i<8;i++){var t=new SongProject.Track("Track "+(i+1),i%2==0?0x577e87:0x876d80);t.nameKey="track.default";s.tracks.add(t);}s.clips.add(Clip.pattern(0,0,0,4*SongProject.PPQ));s.clips.add(Clip.pattern(0,1,4*4*SongProject.PPQ,4*SongProject.PPQ));s.validate();return s;
    }
    static void tick(EditorScreen screen){
        Minecraft mc=Minecraft.getInstance();if(mc.getOverlay()!=null)return;
        if(root==null){net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.sound.PlaySoundEvent e)->{if(e.getSound()!=null&&e.getSound().getLocation().toString().startsWith("musicstudio:sample/"))sampleTriggers++;});root=mc.gameDirectory.toPath().resolve("musicstudio/arrangement-smoke-"+System.currentTimeMillis());try{Files.createDirectories(root);float[] pcm=new float[88200];for(int i=0;i<pcm.length;i++)pcm[i]=(float)(Math.sin(i*2*Math.PI*220/44100)*(.15+.6*i/pcm.length));AudioFiles.wav(root.resolve("source.wav"),new AudioFiles.Wave(pcm,pcm.clone()),1);}catch(Exception ex){throw new IllegalStateException(ex);}}
        if(++ticks>4000)throw new IllegalStateException("ARRANGEMENT_SMOKE timed out at "+stage);if(++age<8)return;
        if(stage==230){try{Files.writeString(root.resolve("complete.txt"),"Whole Patterns; mixed lanes; audio clips/sampler; media/building; Pattern deletion/undo; source replacement; Piano window, scroll, zoom and audition; all window controls; export ranges; Piano/Playlist transport; independent Piano time viewport, dynamic scrollbar, automatic Pattern/linked clip growth through mouse draw/move/resize/duration/paste, 500-bar save, undo, edge autopan, source/Pattern dropdowns, search, scroll, keyboard selection, sampler editing, independent audio source cloning and actual extended OpenAL pitch, PCM, blueprint and Piano playback; sixth-bar edits after cropping, restored old source windows, long Pattern placement, portable reload and audio/Pattern placement selection; automatic full-song audio/MIDI/building end without project padding, decoded low-pitch tails, explicit selected rests and unchanged source project verified.");}catch(Exception ex){throw new IllegalStateException(ex);}MusicStudio.LOGGER.info("ARRANGEMENT_SMOKE PASS: all stages; artifacts {}",root);screen.canvas.smokeCloseRoot();mc.stop();return;}
        if(screen.canvas.smokeArrangementStep(stage,root)){
            if(Set.of(5,11,17,20,31,33,37,47,58,64,65,71,75,80,89,94,104,114,134,142,147,151,153,163,168,171,174,177,181,185,188,194,196,217,219,223).contains(stage))Screenshot.grab(mc.gameDirectory,"musicstudio-arrangement-"+stage+".png",mc.getMainRenderTarget(),c->{});
            MusicStudio.LOGGER.info("ARRANGEMENT_SMOKE PASS stage {}",stage);stage++;age=0;
        }
    }
}

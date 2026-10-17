package dev.musicstudio.song;

import dev.musicstudio.blueprint.*;
import dev.musicstudio.media.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BlueprintNoteExportTest {
    @TempDir Path temp;
    private SongProject song(){var s=BlueprintCompilerTest.tune(false);s.channels.getFirst().volume=1;s.patterns.getFirst().notes.getFirst().clear();return s;}
    @Test void drawnNoteBlocksStartAtNativeDefaultsWithoutChangingSampleDrawing(){
        var s=song();var c=s.channels.getFirst();var n=BlueprintAudio.drawnNote(c,0,66,SongProject.PPQ,2);assertEquals(SongProject.STEP,n.duration);assertEquals(127,n.velocity);
        c.sound="minecraft:ambient.cave";n=BlueprintAudio.drawnNote(c,0,66,SongProject.PPQ,2);assertEquals(2*SongProject.STEP,n.duration);assertEquals(100,n.velocity);
    }
    @Test void oneTrackCanMixNativeVelocityCommandsAndBakedDurationCommands(){
        var s=song();var ns=s.patterns.getFirst().notes.getFirst();ns.add(new SongProject.Note(0,1,66,127));ns.add(new SongProject.Note(2,1,66,64));ns.add(new SongProject.Note(4,2,66,127));
        var event=s.events(0,false).get(2);s.blueprintAudio.put(BlueprintAudio.key(s,s.channels.getFirst(),event),"a".repeat(64));
        String before=ProjectStore.encode(s);var b=MusicBlueprint.compile(s);assertEquals(before,ProjectStore.encode(s));assertFalse(s.commandSamples);
        assertEquals(1,b.parts().stream().filter(p->p.block().equals("note_block")).count());assertEquals(2,b.parts().stream().filter(p->p.block().equals("command_block")).count());assertEquals(2,b.shortened());
        var commands=b.parts().stream().filter(p->p.block().equals("command_block")).map(MusicBlueprint.Part::command).toList();assertTrue(commands.stream().anyMatch(c->c.startsWith("playsound minecraft:block.note_block.harp ")&&c.contains("0.503937")));assertTrue(commands.stream().anyMatch(c->c.startsWith("playsound musicstudio:sample/"+"a".repeat(64))&&c.endsWith("1.000000 1.000000 0")));
    }
    @Test void durationCannotSilentlyFallBackToUneditedPlaysoundAndRestoringDefaultsRestoresNoteBlock(){
        var s=song();var n=new SongProject.Note(0,2,66,127);s.patterns.getFirst().notes.getFirst().add(n);
        assertEquals("error.blueprint_prepare",assertThrows(StudioException.class,()->MusicBlueprint.compile(s)).key);assertTrue(MusicBlueprint.preview(s).outputs().getFirst().command());n.duration=SongProject.STEP;n.velocity=126;assertTrue(MusicBlueprint.compile(s).outputs().getFirst().command());n.velocity=127;assertFalse(MusicBlueprint.compile(s).outputs().getFirst().command());
    }
    @Test void mutedTrackCannotConsumeVoicesChordsBlocksOrPreparation(){
        var s=song();s.patterns.getFirst().notes.getFirst().add(new SongProject.Note(0,1,66,127));var original=MusicBlueprint.compile(s);
        var muted=new SongProject.Channel("Muted", "minecraft:ambient.cave",0);muted.muted=true;s.channels.add(muted);var notes=new ArrayList<SongProject.Note>();for(int i=0;i<32;i++)notes.add(new SongProject.Note(0,12,66,1));s.patterns.getFirst().notes.add(notes);s.clips.add(new SongProject.Clip(1,0,0,1));
        var b=MusicBlueprint.compile(s);assertEquals(original.parts(),b.parts());assertEquals(original.voices(),b.voices());assertEquals(original.notes(),b.notes());assertEquals(original.x(),b.x());assertEquals(original.y(),b.y());assertEquals(original.z(),b.z());
    }
    @Test void editedDurationIsActuallyBakedAndPitchAndVelocityAreNotAppliedTwice() throws Exception {
        var s=song();var c=s.channels.getFirst();var event=new SongProject.Event(0,0,2*SongProject.STEP,78,64);float[] left=new float[44100];for(int i=0;i<left.length;i++)left[i]=(float)(.5*Math.sin(2*Math.PI*220*i/44100));var source=new AudioFiles.Source(new AudioFiles.Wave(left,left.clone()),.5f,1);
        var wave=AudioFiles.noteAudio(source,c,event,120);assertEquals(.25,wave.seconds(),1e-6);assertEquals(source.wave().left()[20]*.5,wave.left()[10],1e-6);assertEquals(44100,source.wave().frames());
        String original=ProjectStore.encode(s);Path published=temp.resolve("published");String hash=AudioFiles.prepareNote(temp,s,source,c,event,AudioFiles.codec(temp),published);assertEquals(original,ProjectStore.encode(s));Path decoded=temp.resolve("decoded.wav");AudioFiles.run(AudioFiles.codec(temp),List.of("-i",published.resolve(hash+".ogg").toString(),decoded.toString()));assertEquals(.25,AudioFiles.read(decoded).seconds(),.03);
    }
    @Test void buildingSnapshotKeepsScoreAndOtherExportsUnmodified() throws Exception {
        var s=song();s.patterns.getFirst().notes.getFirst().add(new SongProject.Note(0,2,66,64));String original=ProjectStore.encode(s);var building=ProjectStore.copy(s);var e=building.events(0,false).getFirst();building.blueprintAudio.put(BlueprintAudio.key(building,building.channels.getFirst(),e),"b".repeat(64));MusicBlueprint.compile(building);
        var imported=MidiFiles.read(MidiFiles.exportTracks(s,temp.resolve("midi")).getFirst()).song().patterns.getFirst().notes.getFirst().getFirst();assertEquals(2*SongProject.STEP,imported.duration);assertEquals(64,imported.velocity);assertEquals(original,ProjectStore.encode(s));assertTrue(s.blueprintAudio.isEmpty());assertEquals("minecraft:block.note_block.harp",s.channels.getFirst().sound);
    }
    @Test void existingVersionThreeNoteAudioKeysRemainPlayable(){
        var s=song();s.patterns.getFirst().notes.getFirst().add(new SongProject.Note(0,2,66,127));var e=s.events(0,false).getFirst();var c=s.channels.getFirst();
        String legacy=ProjectMedia.hash((c.sound+":"+c.baseMidi+":"+c.tunable+":"+e.pitch()+":"+e.duration()+":"+s.bpm).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        s.blueprintAudio.put(legacy,"c".repeat(64));var b=MusicBlueprint.compile(s);assertEquals(1,b.notes().size());assertEquals("musicstudio:sample/"+"c".repeat(64),b.voices().getFirst().sound());
    }
}

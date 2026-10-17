package dev.musicstudio.song;

import dev.musicstudio.blueprint.*;
import dev.musicstudio.media.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sound.midi.MidiSystem;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClipResizeTest {
    @TempDir Path temp;
    private static final int BAR=4*SongProject.PPQ;
    private static SongProject tune(){
        var s=BlueprintCompilerTest.tune(false);s.endPpq=8*BAR;
        var p=s.patterns.getFirst();p.notes.getFirst().clear();
        p.notes.getFirst().add(new SongProject.Note(0,1,66,127));
        p.notes.getFirst().add(new SongProject.Note(8,4,69,127));
        s.clips.getFirst().start=2*BAR;return s;
    }
    @Test void rightPaddingDoesNotRepeatAndTrimCanBeRestored(){
        var s=tune();var c=s.clips.getFirst();var before=s.events(0,false);
        c.resize(c.start,6*BAR);s.validate();
        assertEquals(before,s.events(0,false));assertEquals(BAR,c.contentLength);
        c.resize(c.start,c.start+SongProject.STEP);assertEquals(1,s.events(0,false).size());
        c.resize(c.start,7*BAR);assertEquals(before,s.events(0,false));
    }
    @Test void leftPaddingKeepsAbsoluteNotesAndCropTruncatesCrossingNotes(){
        var s=tune();var c=s.clips.getFirst();var before=s.events(0,false);
        c.resize(BAR,3*BAR);assertEquals(BAR,c.contentOffset);assertEquals(before,s.events(0,false));
        int crop=2*BAR+9*SongProject.STEP;c.resize(crop,3*BAR);
        var e=s.events(0,false).getFirst();assertEquals(crop,e.start());assertEquals(3*SongProject.STEP,e.duration());assertEquals(1,s.events(0,false).size());
        c.resize(BAR,4*BAR);assertEquals(before,s.events(0,false));s.validate();
    }
    @Test void existingRepeatedClipsRemainRepeatedButExtensionAddsRest(){
        var s=tune();var c=s.clips.getFirst();c.duration=3*BAR;
        var before=s.events(0,false);assertEquals(6,before.size());
        c.resize(c.start,7*BAR);assertEquals(before,s.events(0,false));
        c.resize(4*BAR,7*BAR);assertEquals(before.subList(4,6),s.events(0,false));
        c.resize(BAR,8*BAR);assertEquals(before,s.events(0,false));
    }
    @Test void movingAndCopyingPreserveBothBlankMarginsAndSourceWindow(){
        var s=tune();var c=s.clips.getFirst();c.resize(BAR,4*BAR);
        c.start+=BAR;var expected=s.events(0,false);assertEquals(3*BAR,expected.getFirst().start());
        var clipboard=EditorClipboard.copyClips(s,Set.of(c.id));var ids=clipboard.pasteClips(s,5*BAR);
        var pasted=s.clips.stream().filter(k->ids.contains(k.id)).findFirst().orElseThrow();
        assertEquals(c.contentOffset,pasted.contentOffset);assertEquals(c.contentLength,pasted.contentLength);assertEquals(c.duration,pasted.duration);
        assertEquals(6*BAR,s.clipEvents(pasted,pasted.start,pasted.start+pasted.duration).getFirst().start());s.validate();
    }
    @Test void legacyLoadingAndPortableAndBlueprintSnapshotsKeepExactWindows() throws Exception {
        var s=tune();var json=ProjectStore.GSON.fromJson(ProjectStore.encode(s),com.google.gson.JsonObject.class);
        json.getAsJsonArray("clips").forEach(k->{k.getAsJsonObject().remove("contentOffset");k.getAsJsonObject().remove("contentLength");});
        assertEquals(s.events(0,false),ProjectStore.decode(json.toString()).events(0,false));
        s.clips.getFirst().resize(BAR,5*BAR);var file=temp.resolve("rest.musicstudio");ProjectFiles.save(file,temp,s);
        var loaded=ProjectFiles.open(file,temp);assertEquals(ProjectStore.encode(s),ProjectStore.encode(loaded));
        assertEquals(ProjectStore.encode(s),ProjectStore.encode(BlueprintData.unpack(BlueprintData.pack(s))));
    }
    @Test void waveformAudioMidiAndNativeLoopKeepPaddedDuration() throws Exception {
        var s=tune();var c=s.clips.getFirst();c.start=0;c.resize(0,2*BAR);s.endPpq=c.duration;
        s.patterns.getFirst().notes.getFirst().get(1).duration=SongProject.STEP;
        float[] tone=new float[4410];Arrays.fill(tone,.25f);
        var mixed=AudioFiles.mix(s,ch->new AudioFiles.Source(new AudioFiles.Wave(tone,tone.clone()),1,1),0,false,()->false);
        assertEquals(4*44100,mixed.wave().frames());
        for(int i=3*44100;i<4*44100;i++)assertEquals(0,mixed.wave().left()[i]);
        s.exportStart=0;s.exportEnd=s.endPpq;var midi=MidiSystem.getSequence(MidiFiles.exportTracks(s,temp.resolve("midi")).getFirst().toFile());
        assertEquals(s.endPpq,midi.getTickLength());
        var blueprint=MusicBlueprint.compile(s);assertEquals(80,blueprint.period());assertEquals(2,blueprint.notes().size());
        s.loop=true;var loop=MusicBlueprint.compile(s);assertEquals(80,loop.period());assertEquals(2,loop.notes().size());
    }
    @Test void impossibleAndCorruptWindowsAreRejected(){
        var s=tune();var c=s.clips.getFirst();assertThrows(StudioException.class,()->c.resize(-1,BAR));assertThrows(StudioException.class,()->c.resize(0,0));
        c.contentLength=-1;assertThrows(StudioException.class,s::validate);c.contentLength=0;c.contentOffset=1;assertThrows(StudioException.class,s::validate);
    }
}
